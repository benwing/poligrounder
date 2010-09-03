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
package opennlp.textgrounder.bayesian.rlda.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import opennlp.textgrounder.bayesian.apps.ExperimentParameters;
import opennlp.textgrounder.bayesian.structs.NormalizedProbabilityWrapper;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public abstract class OutputWriter extends IOBase {

    /**
     * 
     * @param _experimentParameters
     */
    public OutputWriter(ExperimentParameters _experimentParameters) {
        super(_experimentParameters);
        tokenArrayFile = new File(experimentParameters.getTokenArrayOutputPath());
        probabilitiesFile = new File(experimentParameters.getSampledProbabilitiesPath());
    }

    /**
     *
     */
    public abstract void openTokenArrayWriter();

    /**
     *
     */
    public abstract void writeTokenArray(
          int[] _wordVector, int[] _documentVector, int[] _toponymVector,
          int[] _stopwordVector, int[] _regionVector);

    /**
     * 
     * @param _normalizedProbabilityWrapper
     */
    public void writeProbabilities(
          NormalizedProbabilityWrapper _normalizedProbabilityWrapper) {
        ObjectOutputStream probOut = null;
        try {
            probOut = new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(probabilitiesFile.getCanonicalPath())));
            probOut.writeObject(_normalizedProbabilityWrapper);
            probOut.close();
        } catch (IOException ex) {
            Logger.getLogger(OutputWriter.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
    }
}