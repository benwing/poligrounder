///////////////////////////////////////////////////////////////////////////////
//  Copyright 2010 Taesun Moon <tsunmoon@gmail.com>.
// 
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
// 
//       http://www.apache.org/licenses/LICENSE-2.0
// 
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//  under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.rlda.models;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import opennlp.rlda.annealers.*;
import opennlp.rlda.apps.ExperimentParameters;
import opennlp.rlda.ec.util.MersenneTwisterFast;
import opennlp.rlda.io.BinaryInputReader;
import opennlp.rlda.io.BinaryOutputWriter;
import opennlp.rlda.io.InputReader;
import opennlp.rlda.io.OutputWriter;
import opennlp.rlda.io.TextInputReader;
import opennlp.rlda.structs.IntDoublePair;
import opennlp.rlda.structs.NormalizedProbabilityWrapper;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public class RegionModel extends RegionModelFields {

    /**
     * Random number generator. Implements the fast Mersenne Twister.
     */
    protected transient MersenneTwisterFast rand;
    /**
     * Handles simulated annealing, burn-in, and full sampling cycle
     */
    protected transient Annealer annealer;
    /**
     * 
     */
    protected transient ExperimentParameters experimentParameters;
    /**
     * 
     */
    protected transient InputReader inputReader;
    /**
     * 
     */
    protected transient OutputWriter outputWriter;
    /**
     *
     */
    protected transient final static int outputPerClass = 1000;
    /**
     * 
     */
    protected transient IntDoublePair[][] topWordsPerRegion = null;

    /**
     * Default constructor. Take input from commandline and default _options
     * and initialize class. Also, process input text and process so that
     * toponyms, stopwords and other words are identified and collected.
     *
     * @param _options
     */
    public RegionModel(ExperimentParameters _parameters) {
        experimentParameters = _parameters;
    }

    /**
     *
     * @param _options
     */
    protected final void initialize(ExperimentParameters _experimentParameters) {

        switch (_experimentParameters.getInputFormat()) {
            case BINARY:
                inputReader = new BinaryInputReader(_experimentParameters);
                break;
            case TEXT:
                inputReader = new TextInputReader(_experimentParameters);
                break;
        }

        alpha = _experimentParameters.getAlpha();
        beta = _experimentParameters.getBeta();
        betaW = beta * W;

        int randSeed = _experimentParameters.getRandomSeed();
        if (randSeed == 0) {
            /**
             * Case for complete random seeding
             */
            rand = new MersenneTwisterFast();
        } else {
            /**
             * Case for non-random seeding. For debugging. Also, the default
             */
            rand = new MersenneTwisterFast(randSeed);
        }

        double targetTemp = _experimentParameters.getTargetTemperature();
        double initialTemp = _experimentParameters.getInitialTemperature();
        if (Math.abs(initialTemp - targetTemp) < Annealer.EPSILON) {
            annealer = new EmptyAnnealer(_experimentParameters);
        } else {
            annealer = new SimulatedAnnealer(_experimentParameters);
        }

        readTokenArrayFile();
        readRegionToponymFilter();
        buildActiveRegionByDocumentFilter();
    }

    public void initialize() {
        initialize(experimentParameters);
    }

    protected void readTokenArrayFile() {

        HashSet<Integer> stopwordSet = new HashSet<Integer>();
        ArrayList<Integer> wordArray = new ArrayList<Integer>(),
              docArray = new ArrayList<Integer>(),
              toponymArray = new ArrayList<Integer>(),
              stopwordArray = new ArrayList<Integer>();

        try {
            while (true) {
                int[] record = inputReader.nextTokenArrayRecord();
                if (record != null) {
                    int wordid = record[0];
                    wordArray.add(wordid);
                    int docid = record[1];
                    docArray.add(docid);
                    int topstatus = record[2];
                    toponymArray.add(topstatus);
                    int stopstatus = record[3];
                    stopwordArray.add(stopstatus);
                    if (stopstatus == 1) {
                        stopwordSet.add(wordid);
                    } else {
                        if (W < wordid) {
                            W = wordid;
                        }
                    }
                    if (D < docid) {
                        D = docid;
                    }
                }
            }
        } catch (EOFException ex) {
        } catch (IOException ex) {
            Logger.getLogger(RegionModel.class.getName()).log(Level.SEVERE, null, ex);
        }

        W += 1;
        D += 1;
        N = wordArray.size();

        wordVector = new int[N];
        copyToArray(wordVector, wordArray);

        documentVector = new int[N];
        copyToArray(documentVector, docArray);

        toponymVector = new int[N];
        copyToArray(toponymVector, toponymArray);

        stopwordVector = new int[N];
        if (stopwordArray.size() == N) {
            copyToArray(stopwordVector, stopwordArray);
        } else {
            for (int i = 0; i < N; ++i) {
                stopwordVector[i] = 0;
            }
        }
        regionVector = new int[N];
    }

    /**
     * 
     * @param _file
     */
    public void readRegionToponymFilter() {

        R = inputReader.getMaxRegionID();
        inputReader.resetToponymRegionReader();

        regionByToponymFilter = new int[R * W];
        for (int i = 0; i < R * W; ++i) {
            regionByToponymFilter[i] = 0;
        }

        try {
            while (true) {
                int[] regions = inputReader.nextToponymRegionFilter();

                int topid = regions[0];
                int topoff = topid * R;

                for (int i = 1; i < regions.length; ++i) {
                    regionByToponymFilter[topoff + regions[i]] = 1;
                }
            }
        } catch (EOFException e) {
        } catch (IOException ex) {
            Logger.getLogger(RegionModel.class.getName()).log(Level.SEVERE, null, ex);
        }

        regionCounts = new int[R];
        for (int i = 0; i < R; ++i) {
            regionCounts[i] = 0;
        }
        regionByDocumentCounts = new int[D * R];
        for (int i = 0; i < D * R; ++i) {
            regionByDocumentCounts[i] = 0;
        }
        wordByRegionCounts = new int[W * R];
        for (int i = 0; i < W * R; ++i) {
            wordByRegionCounts[i] = 0;
        }
    }

    protected void buildActiveRegionByDocumentFilter() {
        activeRegionByDocumentFilter = new int[D * R];

        for (int i = 0; i < D * R; ++i) {
            activeRegionByDocumentFilter[i] = 0;
        }

        for (int i = 0; i < N; ++i) {
            int docid = documentVector[i];
            int docoff = docid * R;
            int wordid = wordVector[i];
            int topoff = wordid * R;
            int topstatus = toponymVector[i];
            int stopstatus = stopwordVector[i];

            try {
                if (topstatus == 1 && stopstatus == 0) {
                    for (int j = 0; j < R; ++j) {
                        activeRegionByDocumentFilter[docoff + j] = regionByToponymFilter[topoff + j];
                    }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println(String.format("%d\t%d\t%d\t%d", docid, docoff, wordid, topoff));
            }
        }
    }

    /**
     * Randomly initialize fields for training. If word is a toponym, choose
     * random region only from regions aligned to name.
     */
    public void randomInitialize() {
        int wordid, docid, regionid;
        int istoponym, isstopword;
        int wordoff, docoff;
        double[] probs = new double[R];
        double totalprob, max, r;

        for (int i = 0; i < N; ++i) {
            isstopword = stopwordVector[i];
            if (isstopword == 0) {
                wordid = wordVector[i];
                docid = documentVector[i];
                docoff = docid * R;
                istoponym = toponymVector[i];

                totalprob = 0;
                if (istoponym == 1) {
                    wordoff = wordid * R;
                    try {
                        for (int j = 0;; ++j) {
                            totalprob += probs[j] =
                                  regionByToponymFilter[wordoff + j]
                                  * activeRegionByDocumentFilter[docoff + j];
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                    }
                } else {
                    try {
                        for (int j = 0;; ++j) {
                            totalprob += probs[j] =
                                  activeRegionByDocumentFilter[docoff + j];
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                    }
                }

                r = rand.nextDouble() * totalprob;

                max = probs[0];
                regionid = 0;
                while (r > max) {
                    regionid++;
                    max += probs[regionid];
                }

                regionVector[i] = regionid;
                regionCounts[regionid]++;
                regionByDocumentCounts[docid * R + regionid]++;
                wordByRegionCounts[wordid * R + regionid]++;
            }
        }
    }

    /**
     * Train topics
     *
     * @param decoder Annealing scheme to use
     */
    public void train(Annealer _annealer) {
        int wordid, docid, regionid;
        int wordoff, docoff;
        int istoponym, isstopword;
        double[] probs = new double[R];
        double totalprob, max, r;

        while (_annealer.nextIter()) {
            for (int i = 0; i < N; ++i) {
                isstopword = stopwordVector[i];
                if (isstopword == 0) {
                    wordid = wordVector[i];
                    docid = documentVector[i];
                    regionid = regionVector[i];
                    istoponym = toponymVector[i];
                    docoff = docid * R;
                    wordoff = wordid * R;

                    regionCounts[regionid]--;
                    regionByDocumentCounts[docoff + regionid]--;
                    wordByRegionCounts[wordoff + regionid]--;

                    try {
                        if (istoponym == 1) {
                            for (int j = 0;; ++j) {
                                probs[j] = (wordByRegionCounts[wordoff + j] + beta)
                                      / (regionCounts[j] + betaW)
                                      * (regionByDocumentCounts[docoff + j] + alpha)
                                      * regionByToponymFilter[wordoff + j];
//                                      * activeRegionByDocumentFilter[docoff + j];
                            }
                        } else {
                            for (int j = 0;; ++j) {
                                probs[j] = (wordByRegionCounts[wordoff + j] + beta)
                                      / (regionCounts[j] + betaW)
                                      * (regionByDocumentCounts[docoff + j] + alpha);
                            }
                        }
                    } catch (ArrayIndexOutOfBoundsException e) {
                    }
                    totalprob = _annealer.annealProbs(probs);
                    r = rand.nextDouble() * totalprob;

                    max = probs[0];
                    regionid = 0;
                    while (r > max) {
                        regionid++;
                        max += probs[regionid];
                    }
                    regionVector[i] = regionid;

                    regionCounts[regionid]++;
                    regionByDocumentCounts[docoff + regionid]++;
                    wordByRegionCounts[wordoff + regionid]++;
                }
            }

            _annealer.collectSamples(regionCounts, wordByRegionCounts, regionByDocumentCounts);
        }
    }

    public void train() {
        System.err.println(String.format("Randomly initializing with %d tokens, %d words, %d regions, %d documents", N, W, R, D));
        randomInitialize();
        System.err.println(String.format("Beginning training with %d tokens, %d words, %d regions, %d documents", N, W, R, D));
        train(annealer);
        if (annealer.getSamples() != 0) {
            normalizedRegionCounts = annealer.getNormalizedTopicSampleCounts();
            normalizedWordByRegionCounts = annealer.getNormalizedWordByTopicSampledProbs();
            normalizedRegionByDocumentCounts = annealer.getNormalizedRegionByDocumentSampledCounts();
        }
    }

    public void decode() {
        System.err.println(String.format("Decoding maximum posterior topics"));
        Annealer decoder = new MaximumPosteriorDecoder();
        int wordid, docid, regionid;
        int wordoff, docoff;
        int istoponym, isstopword;
        double[] probs = new double[R];
        double totalprob, max, r;

        for (int i = 0; i < N; ++i) {
            isstopword = stopwordVector[i];
            if (isstopword == 0) {
                wordid = wordVector[i];
                docid = documentVector[i];
                regionid = regionVector[i];
                istoponym = toponymVector[i];
                docoff = docid * R;
                wordoff = wordid * R;

                try {
                    if (istoponym == 1) {
                        for (int j = 0;; ++j) {
                            probs[j] = (normalizedWordByRegionCounts[wordoff + j] + beta)
                                  / (normalizedRegionCounts[j] + betaW)
                                  * (normalizedRegionByDocumentCounts[docoff + j] + alpha)
                                  * regionByToponymFilter[wordoff + j];
//                                      * activeRegionByDocumentFilter[docoff + j];
                        }
                    } else {
                        for (int j = 0;; ++j) {
                            probs[j] = (normalizedWordByRegionCounts[wordoff + j] + beta)
                                  / (normalizedRegionCounts[j] + betaW)
                                  * (normalizedRegionByDocumentCounts[docoff + j] + alpha);
                        }
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                }
                totalprob = decoder.annealProbs(probs);
                r = rand.nextDouble() * totalprob;

                max = probs[0];
                regionid = 0;
                while (r > max) {
                    regionid++;
                    max += probs[regionid];
                }
                regionVector[i] = regionid;
            }
        }
    }

    public void normalize() {
        throw new UnsupportedOperationException("Normalization not a valid operation in this program");
    }

    /**
     *
     * @param _outputFilename
     * @throws IOException
     */
    public void saveModel(String _outputFilename) throws IOException {
        ObjectOutputStream modelOut =
              new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(_outputFilename + ".gz")));
        modelOut.writeObject(this);
        modelOut.close();
    }

    public void write() {
        outputWriter = new BinaryOutputWriter(experimentParameters);
        outputWriter.writeTokenArray(wordVector, documentVector, toponymVector, stopwordVector, regionVector);

        NormalizedProbabilityWrapper normalizedProbabilityWrapper = new NormalizedProbabilityWrapper(this);
        normalizedProbabilityWrapper.addHyperparameters();

        outputWriter.writeProbabilities(normalizedProbabilityWrapper);
    }

    /**
     * Copy a sequence of numbers from ta to array ia.
     *
     * @param <T>   Any number type
     * @param ia    Target array of integers to be copied to
     * @param ta    Source List<T> of numbers to be copied from
     */
    protected static <T extends Number> void copyToArray(int[] ia,
          ArrayList<T> ta) {
        for (int i = 0; i < ta.size(); ++i) {
            ia[i] = ta.get(i).intValue();
        }
    }
}
