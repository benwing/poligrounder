///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Taesun Moon, The University of Texas at Austin
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
package opennlp.textgrounder.textstructs;

import edu.stanford.nlp.ie.crf.*;
import edu.stanford.nlp.ling.CoreAnnotations.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import opennlp.textgrounder.util.*;

/**
 * Class that reads raw text from a file, runs it through a named-entity
 * recognizer (NER) to split the text into tokens and identify which tokens are
 * locations (toponyms), and extract the resulting tokens and their properties
 * (e.g. whether toponym, stopword, etc.), adding them to a TokenArrayBuffer
 * (which sequentially accumulates tokens across multiple documents).
 * 
 * Multi-word location tokens (e.g. New York) are joined by the code below into
 * a single token.
 * 
 * Actual processing of a file is done in the processFile() method.
 * 
 * FIXME: This code is specific to the Stanford NER. Code to interface to
 * different NER's should be abstracted into separate classes.
 * 
 * @author
 */
public class TextProcessor {

    /**
     * NER system. This uses the Stanford NER system {@link TO COME}.
     */
    public CRFClassifier classifier;
    /**
     * Table of words and indexes.
     */
    protected Lexicon lexicon;
    /**
     * the index (or offset) of the current document being
     * examined
     */
    protected int currentDoc = 0;
    /**
     * The number of paragraphs to treat as a single document.
     */
    protected final int parAsDocSize;

    /**
     * Constructor only to be used with derived classes
     * 
     * @param lexicon
     * @throws ClassCastException
     * @throws IOException
     * @throws ClassNotFoundException
     */
    protected TextProcessor(Lexicon lexicon) throws ClassCastException,
          IOException, ClassNotFoundException {
        parAsDocSize = Integer.MAX_VALUE;
        Properties myClassifierProperties = new Properties();
        classifier = new CRFClassifier(myClassifierProperties);
        classifier.loadClassifier(Constants.STANFORD_NER_HOME + "/classifiers/ner-eng-ie.crf-3-all2008-distsim.ser.gz");
        this.lexicon = lexicon;
    }

    /**
     * Default constructor. Instantiate CRFClassifier.
     * 
     * @param lexicon lookup table for words and indexes
     * @param parAsDocSize number of paragraphs to use as single document. if
     * set to 0, it will process an entire file intact.
     */
    public TextProcessor(Lexicon lexicon, int parAsDocSize) throws
          ClassCastException, IOException, ClassNotFoundException {
        Properties myClassifierProperties = new Properties();
        classifier = new CRFClassifier(myClassifierProperties);
        classifier.loadClassifier(Constants.STANFORD_NER_HOME + "/classifiers/ner-eng-ie.crf-3-all2008-distsim.ser.gz");

        this.lexicon = lexicon;
        if (parAsDocSize == 0) {
            this.parAsDocSize = Integer.MAX_VALUE;
        } else {
            this.parAsDocSize = parAsDocSize;
        }
    }

    /**
     * Constructor only for classes which either specify their own classifier,
     * or those which override and use the NullClassifier, which does not
     * do any named entity recognition.
     * 
     * @param classifier named entity recognition system
     * @param lexicon lookup table for words and indexes
     * @param parAsDocSize number of paragraphs to use as single document. if
     * set to 0, it will process an entire file intact.
     * @throws ClassCastException
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public TextProcessor(CRFClassifier classifier, Lexicon lexicon,
          int parAsDocSize) throws ClassCastException, IOException,
          ClassNotFoundException {
        this.classifier = classifier;
        this.lexicon = lexicon;
        if (parAsDocSize == 0) {
            this.parAsDocSize = Integer.MAX_VALUE;
        } else {
            this.parAsDocSize = parAsDocSize;
        }
    }

    /**
     * Identify toponyms and populate lexicon from input file.
     * 
     * This method only splits any incoming document into smaller 
     * subdocuments based on Lexicon.parAsDocSize. The actual work of
     * identifying toponyms, converting tokens to indexes, and populating
     * arrays is handled in processText.
     * 
     * @param locationOfFile Path to input. Must be a single file.
     * @param tokenArrayBuffer Buffer that holds the array of token indexes,
     * document indexes, and the toponym indexes.
     * @param stopwordList table that contains the list of stopwords. if
     * this is an instance of NullStopwordList, it will return false
     * through stopwordList.isStopWord for every string token examined
     * (i.e. the token is not a stopword).
     * @throws FileNotFoundException
     * @throws IOException
     */
    public void processFile(String locationOfFile,
          TokenArrayBuffer tokenArrayBuffer, StopwordList stopwordList) throws
          FileNotFoundException, IOException {

        BufferedReader textIn = new BufferedReader(new FileReader(locationOfFile));
        System.out.println("Extracting toponym indices from " + locationOfFile + " ...");

        String curLine = null;
        StringBuffer buf = new StringBuffer();
        int counter = 1;
        System.err.print("Processing document:" + currentDoc + ",");
        while (true) {
            curLine = textIn.readLine();
            if (curLine == null || curLine.equals("")) {
                break;
            }
            buf.append(curLine.replaceAll("[<>]", "").replaceAll("&", "and"));
            buf.append(" ");

            if (counter < parAsDocSize) {
                counter++;
            } else {
                processText(buf.toString(), tokenArrayBuffer, stopwordList);

                buf = new StringBuffer();
                currentDoc += 1;
                counter = 1;
                System.err.print(currentDoc + ",");
            }
        }

        /**
         * Add last lines if they have not been processed and added
         */
        if (counter > 1) {
            processText(buf.toString(), tokenArrayBuffer, stopwordList);
            System.err.print(currentDoc + ",");
        }
        System.err.println();

        assert (tokenArrayBuffer.toponymArrayList.size() == tokenArrayBuffer.wordArrayList.size());
    }

    /**
     * Split `text' into tokens (potentially multi-word). Determine whether each
     * token is a toponym and whether it's a stopword (using `stopwordList').
     * Add each token along with its properties (e.g. whether toponym, stopword,
     * etc.) to `tokenArrayBuffer', which is a sequential list of all tokens
     * seen in all documents processed.
     * 
     * FIXME: This code is specific to the Stanford NER. Code to interface to
     * different NER's should be abstracted into separate classes.
     * 
     * First, the entire input string in `text' is passed to the NER classifier.
     * This splits the text into tokens and determines which ones are toponyms.
     * The output of the classifier will be of the form
     * <p>
     * token/LABEL token/LABELpunctuation/LABEL token/LABEL ...
     * </p>
     * Here's an example:
     * <p>
     * I/O complained/O to/O Microsoft/ORGANIZATION about/O Bill/PERSON
     * Gates/PERSON ./O They/O told/O me/O to/O see/O the/O mayor/O of/O
     * New/LOCATION York/LOCATION ./O
     * </p>
     * 
     * FIXME: The code below, and the first example above, indicates that
     * punctuation is joined to a preceding token without whitespace. The second
     * example (from the Stanford NER FAQ at
     * `http://nlp.stanford.edu/software/crf-faq.shtml') indicates otherwise.
     * What's really going on?
     * 
     * If LABEL is `LOCATION', it is processed as a toponym or as a multiword
     * toponym if necessary. Any word that is not identified as a toponym is
     * processed as a regular token. Note that multi-word locations are output
     * as separate tokens (e.g. "New York" in the above example), but we combine
     * them into a single token.
     * 
     * When adding tokens and toponyms, all LABELs and the preceding slashes are
     * removed and the token is lowercased before being added to the lexicon and
     * to `tokenArrayBuffer'.
     * 
     * This method also adds to a sequence of document indexes and toponym
     * indexes maintained in evalTokenArrayBuffer. The sequences of token,
     * document and toponym indexes are of the same size so that they are
     * coindexed. The toponym sequence is slightly different from the other
     * sequences in that it is only populated by ones and zeros. If the current
     * toponym index is one, it means the current token is a toponym. If it is
     * zero, the current token is not a toponym.
     * 
     * @param text
     *            StringBuffer of input to be processed. This should always be
     *            space delimited raw text.
     * @param tokenArrayBuffer
     *            buffer that holds the array of token indexes, document
     *            indexes, and the toponym indexes.
     * @param stopwordList
     *            table that contains the list of stopwords. if this is an
     *            instance of NullStopwordList, it will return false through
     *            stopwordList.isStopWord for every string token examined (i.e.
     *            the token is not a stopword).
     * @param currentDoc
     */
    public void processText(String text, TokenArrayBuffer tokenArrayBuffer,
            StopwordList stopwordList) {

        /* Get NER output */
        String nerOutput = classifier.classifyToString(text);

        /*
         * Split NER output into space-separated "tokens" (may not correspond
         * with the tokens we store into TokenArrayBuffer, see above)
         */
        String[] tokens = nerOutput.replaceAll("-[LR]RB-", "").split(" ");
        int toponymStartIndex = -1;
        int toponymEndIndex = -1;

        /* For each NER "token", process it */
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            int wordidx = 0;
            boolean isLocationToken = token.contains("/LOCATION");
            if (isLocationToken) {
                /*
                 * We've seen a location token. We have to deal with multi-word
                 * location tokens, so if we see a location token, don't add it
                 * yet to the TokenArrayBuffer, just remember the position
                 */
                if (toponymStartIndex == -1) {
                    toponymStartIndex = i;
                }
                /*
                 * Handle location tokens ending in /O (?)
                 * 
                 * FIXME: When does this happen? Does the output really include
                 * tokens like `foo/LOCATION/O' ?
                 */
                if (token.endsWith("/O")) {
                    toponymEndIndex = i + 1;
                    String cur = StringUtil.join(tokens, " ",
                            toponymStartIndex, toponymEndIndex, "/")
                            .toLowerCase();
                    wordidx = lexicon.addWord(cur);
                    tokenArrayBuffer.addElement(wordidx, currentDoc, 1, 0);

                    toponymStartIndex = -1;
                    toponymEndIndex = -1;
                }
            } else if (toponymStartIndex != -1) {
                /*
                 * We've seen a non-location token, and there was a location
                 * token before us. Add both the location (potentially
                 * multi-word) and the current non-location token.
                 */
                toponymEndIndex = i;
                /**
                 * Add the toponym that spans from toponymStartIndex to
                 * toponymEndIndex. This does not include the current token
                 */
                String cur = StringUtil.join(tokens, " ", toponymStartIndex,
                        toponymEndIndex, "/").toLowerCase();
                wordidx = lexicon.addWord(cur);
                tokenArrayBuffer.addElement(wordidx, currentDoc, 1, 0);

                /**
                 * Add the current token.
                 * 
                 * According to the code below, multiple non-word tokens can be
                 * joined together without spaces, e.g. 'here/O/./O'.
                 * 
                 * See comment above.
                 */
                List<String> subtokes = retrieveWords(token);
                for (String subtoke : subtokes) {
                    if (!subtoke.isEmpty()) {
                        subtoke = subtoke.trim().toLowerCase();
                        int isstop = 0;
                        if (stopwordList.isStopWord(subtoke)) {
                            isstop = 1;
                        }
                        wordidx = lexicon.addWord(subtoke);
                        tokenArrayBuffer.addElement(wordidx, currentDoc, 0,
                                isstop);
                    }
                }

                toponymStartIndex = -1;
                toponymEndIndex = -1;
            } else {
                /*
                 * A non-location token, no location token preceding. Just add
                 * it.
                 * 
                 * FIXME: Duplicated code.
                 */
                List<String> subtokes = retrieveWords(token);
                for (String subtoke : subtokes) {
                    if (!subtoke.isEmpty()) {
                        subtoke = subtoke.trim().toLowerCase();
                        int isstop = 0;
                        if (stopwordList.isStopWord(subtoke)) {
                            isstop = 1;
                        }
                        wordidx = lexicon.addWord(subtoke);
                        tokenArrayBuffer.addElement(wordidx, currentDoc, 0,
                                isstop);
                    }
                }
            }
        }

        // case where toponym ended at very end of document:
        if (toponymStartIndex != -1) {
            int wordidx = lexicon.addWord(StringUtil.join(tokens, " ",
                    toponymStartIndex, toponymEndIndex, "/"));
            tokenArrayBuffer.addElement(wordidx, currentDoc, 1, 0);
        }
    }

    /**
     * Find the non-label tokens in a single string with multiple tokens that
     * does not have whitespace boundaries.
     * 
     * FIXME: Duplicated functionality with two-arg version of function below.
     * 
     * @param token
     * @return
     */
    protected List<String> retrieveWords(String token) {
        String[] sarray = token.split("(/LOCATION|/PERSON|/ORGANIZATION|/O)");
        List<String> valid_tokens = new ArrayList<String>();
        for (String cur : sarray) {
            if (!cur.matches("^\\W*$")) {
                valid_tokens.add(cur);
            }
        }
        return valid_tokens;
    }

    /**
     * Retrieve first string of alphanumeric elements in an array of strings.
     *
     * The Stanford NER will add multiple /O, /LOCATION, /PERSON etc. tags to
     * a single token if it includes punctuation (though there might be other
     * character types that trigger the NER system to add multiple tags). We
     * take this token with multiple tags and split it on the slashes. This
     * method will find the first string that includes only characters
     * that fit the regex <p>^\\W*$</p>. If no such string is found, it
     * returns the empty string "".
     *
     * @param sarray Array of strings.
     * @param idx Index value in array of strings to examine
     * @return String that has only alphanumeric characters
     */
    protected String retrieveWords(String[] sarray, int idx) {
        String cur = null;
        try {
            cur = sarray[idx];
        } catch (ArrayIndexOutOfBoundsException e) {
            return "";
        }

        if (idx != 0 && cur.startsWith("O")) {
            cur = cur.substring(1);
        }

        if (!cur.matches("^\\W*$")) {
            return cur;
        } else {
            return retrieveWords(sarray, idx + 1);
        }
    }
}
