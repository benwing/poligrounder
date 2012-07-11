
//  ProcessTwitterPull.scala
//
//  Copyright (C) 2012 Stephen Roller, The University of Texas at Austin
//  Copyright (C) 2012 Ben Wing, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////

package opennlp.textgrounder.preprocess

import net.liftweb.json
import com.nicta.scoobi.Scoobi._
import java.io._
import java.lang.Double.isNaN
import java.text.{SimpleDateFormat, ParseException}

import util.control.Breaks._

import opennlp.textgrounder.util.Twokenize
import opennlp.textgrounder.util.argparser._
import opennlp.textgrounder.util.printutil._
import opennlp.textgrounder.gridlocate.DistDocument

/*
 * This program takes, as input, files which contain one tweet
 * per line in json format as directly pulled from the twitter
 * API. It combines the tweets either by user or by time, and outputs a
 * folder that may be used as the --input-corpus argument of tg-geolocate.
 * This is in "TextGrounder corpus" format, with one document per line,
 * fields separated by tabs, and all the ngrams and counts placed in a single
 * field, of the form "WORD1:WORD2:...:COUNT WORD1:WORD2:...:COUNT ...".
 *
 * The fields currently output are:
 *
 * 1. user
 * 2. timestamp
 * 3. latitude,longitude
 * 4. number of followers (people following the user)
 * 5. number of people the user is following
 * 6. number of tweets merged to form the per-user document
 * 7. unigram text of combined tweets
 *
 * NOTE: A schema is generated and properly named, but the main file is
 * currently given a name by Scoobi and needs to be renamed to correspond
 * with the schema file: e.g. if the schema file is called
 * "sep-22-debate-training-unigram-counts-schema.txt", then the main file
 * should be called "sep-22-debate-training-unigram-counts.txt".
 *
 * When merging by user, the code uses the earliest geolocated tweet as the
 * user's location; tweets with a bounding box as their location rather than a
 * single point are treated as if they have no location.  Then the code filters
 * out users that have no geolocation or have a geolocation outside of North
 * America (according to a crude bounding box), as well as those that are
 * identified as likely "spammers" according to their number of followers and
 * number of people they are following.
 */

class ProcessTwitterPullParams(ap: ArgParser) {
  // The following is set based on presence or absence of --by-time
  var keytype = "user"
  var timeslice_float = ap.option[Double]("timeslice", "time-slice",
    default = 6.0,
    help="""Number of seconds per timeslice when grouping '--by-time'.
    Can be a fractional number.  Default %default.""")
  // The following is set based on --timeslice
  var timeslice: Long = _
  var corpus_name = ap.option[String]("corpus-name", default = "unknown",
    help="""Name of corpus; for identification purposes.
    Default '%default'.""")
  var split = ap.option[String]("split", default = "training",
    help="""Split (training, dev, test) to place data in.  Default %default.""")
  var filter = ap.option[String]("filter",
    help="""Boolean expression used to filter tweets to be output.
Expression consists of one or more sequences of words, joined by the operators
AND, OR and NOT.  A sequence of words matches a tweet if and only if that exact
sequence is found in the tweet (tweets are matched after they have been
tokenized).  AND has higher precedence than OR.  Parentheses can be used
for grouping or precedence.  Any word that is quoted is treated as a literal
regardless of the characters in it; this can be used to treat words such as
"AND" literally.  Matching is case-insensitive; use '--cfilter' for
case-sensitive matching.  Note that the use of '--preserve-case' has no effect
on the case sensitivity of filtering; it rather affects whether the output
is converted to lowercase or left as-is.

Examples:

--filter "mitt romney OR obama"

Look for any tweets containing the sequence "mitt romney" (in any case) or
"Obama".

--filter "mitt AND romney OR barack AND obama"

Look for any tweets containing either the words "mitt" and "romney" (in any
case and anywhere in the tweet) or the words "barack" and "obama".

--filter "hillary OR bill AND clinton"

Look for any tweets containing either the word "hillary" or both the words
"bill" and "clinton" (anywhere in the tweet).

--filter "(hillary OR bill) AND clinton"

Look for any tweets containing the word "clinton" as well as either the words
"bill" or "hillary".""")
  var cfilter = ap.option[String]("cfilter",
    help="""Boolean expression used to filter tweets to be output, with
    case-sensitive matching.  Format is identical to '--filter'.""")
  var by_time = ap.flag("by-time",
    help="""Group tweets by time instead of by user.  When this is used, all
    tweets within a timeslice of a give number of seconds (specified using
    '--timeslice') are grouped together.""")
  var preserve_case = ap.flag("preserve-case",
    help="""Don't lowercase words.  This preserves the difference
    between e.g. the name "Mark" and the word "mark".""")
  var max_ngram = ap.option[Int]("max-ngram", "max-n-gram", "ngram", "n-gram",
    default = 1,
    help="""Largest size of n-grams to create.  Default 1, i.e. distribution
    only contains unigrams.""")
  var input = ap.positional[String]("INPUT",
    help = "Source directory to read files from.")
  var output = ap.positional[String]("OUTPUT",
    help = "Destination directory to place files in.")
}

import scala.util.parsing.combinator.lexical.StdLexical
import scala.util.parsing.combinator.syntactical._
import scala.util.parsing.input.CharArrayReader.EofCh

/**
 * A class used for filtering tweets using a boolean expression.
 * Parsing of the boolean expression uses Scala parsing combinators.
 *
 * To use, create an instance of this class; then call `parse` to
 * parse an expression into an abstract syntax tree object.  Then use
 * the `matches` method on this object to match against a tweet.
 */
class TweetFilterParser(foldcase: Boolean) extends StandardTokenParsers {
  sealed abstract class Expr {
    /**
     * Check if this expression matches the given sequence of words.
     */
    def matches(x: Seq[String]) =
      if (foldcase)
        check(x map (_.toLowerCase))
      else
        check(x)
    // Not meant to be called externally.
    def check(x: Seq[String]): Boolean
  }

  case class EConst(value: Seq[String]) extends Expr {
    def check(x: Seq[String]) = x containsSlice value
  }

  case class EAnd(left:Expr, right:Expr) extends Expr {
    def check(x: Seq[String]) = left.check(x) && right.check(x)
  }

  case class EOr(left:Expr, right:Expr) extends Expr {
    def check(x: Seq[String]) = left.check(x) || right.check(x)
  }

  case class ENot(e:Expr) extends Expr {
    def check(x: Seq[String]) = !e.check(x)
  }

  class ExprLexical extends StdLexical {
    override def token: Parser[Token] = floatingToken | super.token

    def floatingToken: Parser[Token] =
      rep1(digit) ~ optFraction ~ optExponent ^^
        { case intPart ~ frac ~ exp => NumericLit(
            (intPart mkString "") :: frac :: exp :: Nil mkString "")}

    def chr(c:Char) = elem("", ch => ch==c )
    def sign = chr('+') | chr('-')
    def optSign = opt(sign) ^^ {
      case None => ""
      case Some(sign) => sign
    }
    def fraction = '.' ~ rep(digit) ^^ {
      case dot ~ ff => dot :: (ff mkString "") :: Nil mkString ""
    }
    def optFraction = opt(fraction) ^^ {
      case None => ""
      case Some(fraction) => fraction
    }
    def exponent = (chr('e') | chr('E')) ~ optSign ~ rep1(digit) ^^ {
      case e ~ optSign ~ exp => e :: optSign :: (exp mkString "") :: Nil mkString ""
    }
    def optExponent = opt(exponent) ^^ {
      case None => ""
      case Some(exponent) => exponent
    }
  }

  class FilterLexical extends StdLexical {
    // see `token` in `Scanners`
    override def token: Parser[Token] =
      ( delim
      | unquotedWordChar ~ rep( unquotedWordChar )  ^^
         { case first ~ rest => processIdent(first :: rest mkString "") }
      | '\"' ~ rep( quotedWordChar ) ~ '\"' ^^
         { case '\"' ~ chars ~ '\"' => StringLit(chars mkString "") }
      | EofCh ^^^ EOF
      | '\"' ~> failure("unclosed string literal")
      | failure("illegal character")
      )

    def isPrintable(ch: Char) =
       !ch.isControl && !ch.isSpaceChar && !ch.isWhitespace && ch != EofCh
    def isPrintableNonDelim(ch: Char) =
       isPrintable(ch) && ch != '(' && ch != ')'
    def unquotedWordChar = elem("unquoted word char",
       ch => ch != '"' && isPrintableNonDelim(ch))
    def quotedWordChar = elem("quoted word char",
       ch => ch != '"' && ch != '\n' && ch != EofCh)

   // // see `whitespace in `Scanners`
   // def whitespace: Parser[Any] = rep(
   //     whitespaceChar
   // //  | '/' ~ '/' ~ rep( chrExcept(EofCh, '\n') )
   //   )

    override protected def processIdent(name: String) =
      if (reserved contains name) Keyword(name) else StringLit(name)
  }

  override val lexical = new FilterLexical
  lexical.reserved ++= List("AND", "OR", "NOT")
  // lexical.delimiters ++= List("&","|","!","(",")")
  lexical.delimiters ++= List("(",")")

  def word = stringLit ^^ {
    s => EConst(Seq(if (foldcase) s.toLowerCase else s))
  }

  def words = word.+ ^^ {
    x => EConst(x.flatMap(_ match { case EConst(y) => y }))
  }

  def parens: Parser[Expr] = "(" ~> expr <~ ")"

  def not: Parser[ENot] = "NOT" ~> term ^^ { ENot(_) }

  def term = ( words | parens | not )

  def andexpr = term * (
    "AND" ^^^ { (a:Expr, b:Expr) => EAnd(a,b) } )

  def orexpr = andexpr * (
    "OR" ^^^ { (a:Expr, b:Expr) => EOr(a,b) } )

  def expr = ( orexpr | term )

  def maybe_parse(s: String) = {
    val tokens = new lexical.Scanner(s)
    phrase(expr)(tokens)
  }

  def parse(s: String): Expr = {
    maybe_parse(s) match {
      case Success(tree, _) => tree
      case e: NoSuccess =>
        throw new IllegalArgumentException("Bad syntax: "+s)
    }
  }

  def test(exprstr: String, tweet: Seq[String]) = {
    maybe_parse(exprstr) match {
      case Success(tree, _) =>
        println("Tree: "+tree)
        val v = tree.matches(tweet)
        println("Eval: "+v)
      case e: NoSuccess => errprint("%s\n" format e)
    }
  }
  
  //A main method for testing
  def main(args: Array[String]) = test(args(0), args(1).split("""\s"""))
}

object ProcessTwitterPull extends ScoobiApp {
  // Tweet = Data for a tweet other than the tweet ID =
  // (user, timestamp, text, lat, lng, followers, following, number of tweets)
  // Note that we have "number of tweets" since we merge multiple tweets into
  // a document, and also use type Tweet for them.
  type Tweet = (String, Long, String, Double, Double, Int, Int, Int)
  // TweetID = numeric string used to uniquely identify a tweet.
  type TweetID = String
  // Record = Data for tweet along with the key (e.g. username, timestamp) =
  // (key, tweet data)
  type Record = (String, Tweet)
  // IDRecord = Tweet ID along with all other data for a tweet.
  type IDRecord = (TweetID, Record)
  // TweetNoText = Data for a merged set of tweets other than the text =
  // (username, earliest timestamp, best lat, best lng, max followers,
  //    max following, number of tweets pulled)
  type TweetNoText = (String, Long, Double, Double, Int, Int, Int)
  // TweetNgram = Data for the tweet minus the text, plus an individual ngram
  //   from the text = (tweet_no_text_as_string, ngram)
  type TweetNgram = (String, String)
  // NgramCount = (ngram, number of ocurrences)
  type NgramCount = (String, Long)

  var Opts: ProcessTwitterPullParams = _

  def force_value(value: json.JValue): String = {
    if ((value values) == null)
        null
    else
        (value values) toString
  }

  /**
   * Convert a Twitter timestamp, e.g. "Tue Jun 05 14:31:21 +0000 2012", into
   * a time in milliseconds since the Epoch (Jan 1 1970, or so).
   */
  def parse_time(timestring: String): Long = {
    val sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss ZZZZZ yyyy")
    try {
      sdf.parse(timestring)
      sdf.getCalendar.getTimeInMillis
    } catch {
      case pe: ParseException => 0
    }
  }

  /**
   * Return true if this tweet is "valid" in that it doesn't have any
   * out-of-range values (blank strings or 0-valued quantities).  Note
   * that we treat a case where both latitude and longitude are 0 as
   * invalid even though technically such a place could exist. (FIXME,
   * use NaN or something to indicate a missing latitude or longitude).
   */
  def is_valid_tweet(id_r: IDRecord): Boolean = {
    // filters out invalid tweets, as well as trivial spam
    val (tw_id, (key, (user, ts, text, lat, lng, fers, fing, numtw))) = id_r
    tw_id != "" && ts != 0.0 && user != "" && !(lat == 0.0 && lng == 0.0)
  }

  val MAX_NUMBER_FOLLOWING = 1000
  val MIN_NUMBER_FOLLOWING = 5
  val MIN_NUMBER_FOLLOWERS = 10
  val MIN_NUMBER_TWEETS = 10
  val MAX_NUMBER_TWEETS = 1000
  /**
   * Return true if this tweet combination (tweets for a given user)
   * appears to reflect a "spammer" user or some other user with
   * sufficiently nonstandard behavior that we want to exclude them (e.g.
   * a celebrity or an inactive user): Having too few or too many tweets,
   * following too many or too few, or having too few followers.  A spam
   * account is likely to have too many tweets -- and even more, to send
   * tweets to too many people (although we don't track this).  A spam
   * account sends much more than it receives, and may have no followers.
   * A celebrity account receives much more than it sends, and tends to have
   * a lot of followers.  People who send too few tweets simply don't
   * provide enough data.
   *
   * FIXME: We don't check for too many followers of a given account, but
   * instead too many people that a given account is following.  Perhaps
   * this is backwards?
   */
  def is_nonspammer(r: Record): Boolean = {
    val (key, (user, ts, text, lat, lng, fers, fing, numtw)) = r

    (fing >= MIN_NUMBER_FOLLOWING && fing <= MAX_NUMBER_FOLLOWING) &&
      (fers >= MIN_NUMBER_FOLLOWERS) &&
      (numtw >= MIN_NUMBER_TWEETS && numtw <= MAX_NUMBER_TWEETS)
  }

  // bounding box for north america
  val MIN_LAT = 25.0
  val MIN_LNG = -126.0
  val MAX_LAT = 49.0
  val MAX_LNG = -60.0

  /**
   * Return true of this tweet (combination) is located within the
   * bounding box fo North America.
   */
  def northamerica_only(r: Record): Boolean = {
    val (key, (user, ts, text, lat, lng, fers, fing, numtw)) = r

    (lat >= MIN_LAT && lat <= MAX_LAT) &&
      (lng >= MIN_LNG && lng <= MAX_LNG)
  }


  /**
   * An empty tweet, stored as a full IDRecord.
   */
  val empty_tweet: IDRecord = ("", ("", ("", 0, "", Double.NaN, Double.NaN, 0, 0, 0)))

  /**
   * Parse a JSON line into a tweet.  Return value is an IDRecord, including
   * the tweet ID, username, text and all other data.
   */
  def parse_json(line: String): IDRecord = {
    try {
      val parsed = json.parse(line)
      val user = force_value(parsed \ "user" \ "screen_name")
      val timestamp = parse_time(force_value(parsed \ "created_at"))
      val text = force_value(parsed \ "text").replaceAll("\\s+", " ")
      val followers = force_value(parsed \ "user" \ "followers_count").toInt
      val following = force_value(parsed \ "user" \ "friends_count").toInt
      val tweet_id = force_value(parsed \ "id_str")
      val (lat, lng) = 
        if ((parsed \ "coordinates" values) == null ||
            (force_value(parsed \ "coordinates" \ "type") != "Point")) {
          (Double.NaN, Double.NaN)
        } else {
          val latlng: List[Number] = 
            (parsed \ "coordinates" \ "coordinates" values).asInstanceOf[List[Number]]
          (latlng(1).doubleValue, latlng(0).doubleValue)
        }
      val key = Opts.keytype match {
        case "user" => user
        case _ => ((timestamp / Opts.timeslice) * Opts.timeslice).toString
      }
      (tweet_id, (key, (user, timestamp, text, lat, lng, followers, following, 1)))
    } catch {
      case jpe: json.JsonParser.ParseException => empty_tweet
      case npe: NullPointerException => empty_tweet
      case nfe: NumberFormatException => empty_tweet
    }
  }

  /**
   * Select the first tweet with the same ID.  For various reasons we may
   * have duplicates of the same tweet among our data.  E.g. it seems that
   * Twitter itself sometimes streams duplicates through its Streaming API,
   * and data from different sources will almost certainly have duplicates.
   * Furthermore, sometimes we want to get all the tweets even in the presence
   * of flakiness that causes Twitter to sometimes bomb out in a Streaming
   * session and take a while to restart, so we have two or three simultaneous
   * streams going recording the same stuff, hoping that Twitter bombs out at
   * different points in the different sessions (which is generally true).
   * Then, all or almost all the tweets are available in the different streams,
   * but there is a lot of duplication that needs to be tossed aside.
   */
  def tweet_once(id_rs: (TweetID, Iterable[Record])): Record = {
    val (id, rs) = id_rs
    rs.head
  }

  /**
   * Merge the data associated with two tweets or tweet combinations
   * into a single tweet combination.  Concatenate text.  Find maximum
   * numbers of followers and followees.  Add number of tweets in each.
   * For latitude and longitude, take the earliest provided values
   * ("earliest" by timestamp and "provided" meaning not missing).
   */
  def merge_records(tweet1: Tweet, tweet2: Tweet): Tweet = {
    val (user1, ts1, text1, lat1, lng1, fers1, fing1, numtw1) = tweet1
    val (user2, ts2, text2, lat2, lng2, fers2, fing2, numtw2) = tweet2
    val (fers, fing) = (math.max(fers1, fers2), math.max(fing1, fing2))
    val text = text1 + " " + text2
    val numtw = numtw1 + numtw2

    val (lat, lng, ts) = 
      if (isNaN(lat1) && isNaN(lat2)) {
        (lat1, lng1, math.min(ts1, ts2))
      } else if (isNaN(lat2)) {
        (lat1, lng1, ts1)
      } else if (isNaN(lat1)) {
        (lat2, lng2, ts2)
      } else if (ts1 < ts2) {
        (lat1, lng1, ts1)
      } else {
        (lat2, lng2, ts2)
      }

    // FIXME maybe want to track the different users
    (user1, ts, text, lat, lng, fers, fing, numtw)
  }

  /**
   * Return true if tweet (combination) has a fully-specified latitude
   * and longitude.
   */
  def has_latlng(r: Record) = {
    val (key, (user, ts, text, lat, lng, fers, fing, numtw)) = r
    !isNaN(lat) && !isNaN(lng)
  }

  /**
   * Convert a word to lowercase.
   */
  def normalize_word(orig_word: String) = {
    val word =
      if (Opts.preserve_case)
        orig_word
      else
        orig_word.toLowerCase
    // word.startsWith("@")
    if (word.contains("http://") || word.contains("https://"))
      "-LINK-"
    else
      word
  }

  /**
   * Return true if word should be filtered out (post-normalization).
   */
  def reject_word(word: String) = {
    word == "-LINK-"
  }

  /**
   * Return true if ngram should be filtered out (post-normalization).
   * Here we filter out things where every word should be filtered, or
   * where the first or last word should be filtered (in such a case, all
   * the rest will be contained in a one-size-down n-gram).
   */
  def reject_ngram(ngram: Iterable[String]) = {
    ngram.forall(reject_word) || reject_word(ngram.head) ||
      reject_word(ngram.last)
  }

  /**
   * Convert a "record" (key plus tweet data) into a line of text suitable
   * for writing to a "checkpoint" file.  We encode all the fields into text
   * and separate by tabs.  The text gets moved to the end and preceded by
   * two tabs, so it can be found more easily when re-reading. FIXME: This
   * is fragile; will have problems if any field is blank.  Just peel off
   * the last field, or whatever.
   */
  def checkpoint_str(r: Record): String = {
    val (key, (user, ts, text, lat, lng, fers, fing, numtw)) = r
    val text_2 = text.replaceAll("\\s+", " ")
    val s = Seq(key, user, ts, lat, lng, fers, fing, numtw, "", text_2) mkString "\t"
    s
  }

  /**
   * Convert a checkpointed string generated by `checkpoint_str` back into
   * the record it came from.
   */
  def from_checkpoint_to_record(line: String): Record = {
    val split = line.split("\t\t")
    assert(split.length == 2)
    val (tweet_no_text, text) = (split(0), split(1))
    val (key, (user, ts, lat, lng, fers, fing, numtw)) =
      split_tweet_no_text(tweet_no_text)
    (key, (user, ts, text, lat, lng, fers, fing, numtw))
  }

  def split_tweet_no_text(tweet_no_text: String): (String, TweetNoText) = {
    val split2 = tweet_no_text.split("\t")
    val key = split2(0)
    val user = split2(1)
    val ts = split2(2).toLong
    val lat = split2(3).toDouble
    val lng = split2(4).toDouble
    val fers = split2(5).toInt
    val fing = split2(6).toInt
    val numtw = split2(7).toInt
    (key, (user, ts, lat, lng, fers, fing, numtw))
  }

  def from_checkpoint_to_tweet_text(line: String): (String, String) = {
    val split = line.split("\t\t")
    if (split.length != 2) {
      System.err.println("Bad line: " + line)
      ("", "")
    } else {
      (split(0), split(1))
    }
  }

  def create_parser(expr: String, foldcase: Boolean) = {
    if (expr == null) null
    else new TweetFilterParser(foldcase).parse(expr)
  }

  lazy val filter_ast = create_parser(Opts.filter, foldcase = true)
  lazy val cfilter_ast = create_parser(Opts.cfilter, foldcase = false)

  /**
   * Use Twokenize to break up a tweet into tokens, filter it if necessary
   * according to --filter and --cfilter, and separate into ngrams.
   */
  def break_tweet_into_ngrams(text: String):
      Iterable[Iterable[String]] = {
    val words = Twokenize(text)
    if ((filter_ast == null || (filter_ast matches words)) &&
        (cfilter_ast == null || (cfilter_ast matches words))) {
      val normwords = words.map(normalize_word)

      // Then, generate all possible ngrams up to a specified maximum length,
      // where each ngram is a sequence of words.  `sliding` overlays a sliding
      // window of a given size on a sequence to generate successive
      // subsequences -- exactly what we want to generate ngrams.  So we
      // generate all 1-grams, then all 2-grams, etc. up to the maximum size,
      // and then concatenate the separate lists together (that's what `flatMap`
      // does).
      (1 to Opts.max_ngram).
        flatMap(normwords.sliding(_)).filter(!reject_ngram(_))
    } else
      Iterable[Iterable[String]]()
  }

  /**
   * Given the tweet data minus the text field combined into a string,
   * plus the text field, tokenize the text and emit the ngrams individually.
   * Each ngram is emitted along with the text data and a count of 1,
   * and later grouping + combining will add all the 1's to get the
   * ngram count.
   */
  def emit_ngrams(tweet_text: (String, String)):
      Iterable[(TweetNgram, Long)] = {
    val (tweet_no_text, text) = tweet_text
    for (ngram <- break_tweet_into_ngrams(text))
      yield ((tweet_no_text, DistDocument.encode_ngram_for_counts_field(ngram)),
             1L)
  }

  /**
   * We made the value in the key-value pair be a count so we can combine
   * the counts easily to get the total ngram count, and stuffed all the
   * rest of the data (tweet data plus ngram) into the key, but now we have to
   * rearrange to move the ngram back into the value.
   */
  def reposition_ngram(tnc: (TweetNgram, Long)): (String, NgramCount) = {
    val ((tweet_no_text, ngram), c) = tnc
    (tweet_no_text, (ngram, c))
  }

  /**
   * Given tweet data minus text plus an iterable of ngram-count pairs,
   * convert to a string suitable for outputting.
   */
  def nicely_format_plain(tncs: (String, Iterable[NgramCount])): String = {
    val (tweet_no_text, ncs) = tncs
    val nice_text = ncs.map((w: NgramCount) => w._1 + ":" + w._2).mkString(" ")
    val (key, (user, ts, lat, lng, fers, fing, numtw)) =
      split_tweet_no_text(tweet_no_text)
    // Latitude/longitude need to be combined into a single field, but only
    // if both actually exist.
    val latlngstr =
      if (!isNaN(lat) && !isNaN(lng))
        "%s,%s" format (lat, lng)
      else ""
    // Put back together but drop key.
    Seq(user, ts, latlngstr, fers, fing, numtw, nice_text) mkString "\t"
  }

  /**
   * Output a schema file of the appropriate name.
   */
  def output_schema() {
    val dist_type = if (Opts.max_ngram == 1) "unigram" else "ngram"
    val filename =
      "%s/%s-%s-%s-counts-schema.txt" format
        (Opts.output, Opts.corpus_name, Opts.split, dist_type)
    val p = new PrintWriter(new File(filename))
    def print_seq(s: String*) {
      p.println(s mkString "\t")
    }
    try {
      print_seq("user", "timestamp", "coord", "followers", "following",
        "numtweets", "counts")
      print_seq("corpus", Opts.corpus_name)
      print_seq("corpus-type", "twitter-%s" format Opts.keytype)
      if (Opts.keytype == "timestamp")
        print_seq("corpus-timeslice", Opts.timeslice.toString)
      print_seq("split", Opts.split)
    } finally { p.close() }
  }

  def run() {
    val ap = new ArgParser("ProcessTwitterPull")
    // This first call is necessary, even though it doesn't appear to do
    // anything.  In particular, this ensures that all arguments have been
    // defined on `ap` prior to parsing.
    new ProcessTwitterPullParams(ap)
    ap.parse(args)
    Opts = new ProcessTwitterPullParams(ap)
    if (Opts.by_time)
      Opts.keytype = "timestamp"
    Opts.timeslice = (Opts.timeslice_float * 1000).toLong

    // Firstly we load up all the (new-line-separated) JSON lines.
    val lines: DList[String] = TextInput.fromTextFile(Opts.input)

    // Parse JSON into tweet records (IDRecord), filter out invalid tweets.
    val values_extracted = lines.map(parse_json).filter(is_valid_tweet)

    // Filter out duplicate tweets -- group by Tweet ID and then take the
    // first tweet for a given ID.  Duplicate tweets occur for various
    // reasons, e.g. sometimes in the stream itself due to Twitter errors,
    // or when combining data from multiple, possibly overlapping, sources.
    // In the process, the tweet ID's are discarded.
    val single_tweets = values_extracted.groupByKey.map(tweet_once)

    // Checkpoint the resulting tweets (minus ID) onto disk.
    val checkpoint1 = single_tweets.map(checkpoint_str)
    persist(TextOutput.toTextFile(checkpoint1, Opts.output + "-st"))

    // Then load back up.
    val lines2: DList[String] = TextInput.fromTextFile(Opts.output + "-st")
    val values_extracted2 = lines2.map(from_checkpoint_to_record)

    // Group by username, then combine the tweets for a user into a
    // tweet combination, with text concatenated and the location taken
    // from the earliest/ tweet with a specific coordinate.
    val concatted = values_extracted2.groupByKey.combine(merge_records)

    // If grouping by user, filter the tweet combinations, removing users
    // without a specific coordinate; users that appear to be "spammers" or
    // other users with non-standard behavior; and users located outside of
    // North America.  FIXME: We still want to filter spammers; but this
    // is trickier when not grouping by user.  How to do it?
    val good_tweets =
      if (Opts.keytype == "timestamp") concatted
      else concatted.filter(x =>
             has_latlng(x) &&
             is_nonspammer(x) &&
             northamerica_only(x))

    // Checkpoint a second time.
    val checkpoint = good_tweets.map(checkpoint_str)
    persist(TextOutput.toTextFile(checkpoint, Opts.output + "-cp"))

    // Load from second checkpoint.  Note that each time we checkpoint,
    // we extract the "text" field and stick it at the end.  This time
    // when loading it up, we use `from_checkpoint_to_tweet_text`, which
    // gives us the tweet data as a string (minus text) and the text, as
    // two separate strings.
    val lines_cp: DList[String] = TextInput.fromTextFile(Opts.output + "-cp")

    // Now count ngrams.  We run `from_checkpoint_to_tweet_text` (see above)
    // to get separate access to the text, then Twokenize it into words,
    // generate ngrams from them and emit a series of key-value pairs of
    // (ngram, count).
    val emitted_ngrams = lines_cp.map(from_checkpoint_to_tweet_text)
                                 .flatMap(emit_ngrams)
    // Group based on key (the ngram) and combine by adding up the individual
    // counts.
    val ngram_counts =
      emitted_ngrams.groupByKey.combine((a: Long, b: Long) => a + b)

    // Regroup with proper key (user, timestamp, etc.) as key,
    // ngram pairs as values.
    val regrouped_by_key = ngram_counts.map(reposition_ngram).groupByKey

    // Nice string output.
    val nicely_formatted = regrouped_by_key.map(nicely_format_plain)

    // Save to disk.
    persist(TextOutput.toTextFile(nicely_formatted, Opts.output))

    // create a schema
    output_schema()
  }
}

