import scala.util.parsing.combinator.syntactical.StdTokenParsers
import scala.util.parsing.combinator.lexical.StdLexical


object ArithmeticParser extends StdTokenParsers with Application {
 type Tokens = StdLexical

 val lexical = new StdLexical

 lexical.delimiters ++= List("(", ")", "+", "-", "*", "/")

 def factor: Parser[Int] = "(" ~> expr <~ ")" | numericLit ^^ (_.toInt)

 def term : Parser[Int] = (
   factor ~ "*" ~ term ^^ { case x ~ "*" ~ y => x * y } |
   factor ~ "/" ~ term ^^ { case x ~ "/" ~ y => x / y } | factor )

def expr : Parser[Int] = (
  term ~ "+" ~ expr ^^ { case x ~ "+" ~ y => x + y } |
  term ~ "-" ~ expr ^^ { case x ~ "-" ~ y => x - y } | term )

  def apply(x: String) =
    //(expr (new lexical.Scanner ("1+2*3*7-1") ))
    (expr (new lexical.Scanner (x) ))
}


sealed abstract class Expr {
  def matches(x: Seq[String]): Boolean
}

case class EConst(value: String) extends Expr {
  def matches(x: Seq[String]) = x contains value
}

case class EAnd(left:Expr, right:Expr) extends Expr {
  def matches(x: Seq[String]) = left.matches(x) && right.matches(x)
}

case class EOr(left:Expr, right:Expr) extends Expr {
  def matches(x: Seq[String]) = left.matches(x) || right.matches(x)
}

case class ENot(e:Expr) extends Expr {
  def matches(x: Seq[String]) = !e.matches(x)
}

import scala.util.parsing.combinator.lexical.StdLexical

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

import scala.util.parsing.combinator.syntactical._

object ExprParser extends StandardTokenParsers {
    override val lexical = new ExprLexical
    lexical.delimiters ++= List("&","|","!","(",")")

    def value = numericLit ^^ { s => EConst(s) }

    def parens:Parser[Expr] = "(" ~> expr <~ ")"

    def not:Parser[ENot] = "!" ~> term ^^ { ENot(_) }

    def term = ( value |  parens | not )

    def binaryOp(level:Int):Parser[((Expr,Expr)=>Expr)] = {
        level match {
            case 1 =>
                "|" ^^^ { (a:Expr, b:Expr) => EOr(a,b) }
            case 2 =>
                "&" ^^^ { (a:Expr, b:Expr) => EAnd(a,b) }
            case _ => throw new RuntimeException("bad precedence level "+level)
        }
    }
    val minPrec = 1
    val maxPrec = 2

    def binary(level:Int):Parser[Expr] =
        if (level>maxPrec) term
        else binary(level+1) * binaryOp(level)

    def expr = ( binary(minPrec) | term )

    def parse(s:String) = {
        val tokens = new lexical.Scanner(s)
        phrase(expr)(tokens)
    }

    def apply(s:String):Expr = {
        parse(s) match {
            case Success(tree, _) => tree
            case e: NoSuccess =>
                   throw new IllegalArgumentException("Bad syntax: "+s)
        }
    }

    def test(exprstr: String, tweet: Seq[String]) = {
        parse(exprstr) match {
            case Success(tree, _) =>
                println("Tree: "+tree)
                val v = tree.matches(tweet)
                println("Eval: "+v)
            case e: NoSuccess => Console.err.println(e)
        }
    }
    
    //A main method for testing
    def main(args: Array[String]) = test(args(0), args(1).split("""\s"""))
}
