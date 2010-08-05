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
package opennlp.rlda.apps;

import opennlp.rlda.converters.InternalToXMLConverter;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public class ConvertFromRegionModelFormat {

    public static void main(String[] args) throws Exception {

        ConverterExperimentParameters experimentParameters = new ConverterExperimentParameters();
        ExperimentParameterManipulator.loadParameters(experimentParameters, args[0], "RLDA");
        InternalToXMLConverter converter = new InternalToXMLConverter(experimentParameters);
        converter.convert();
    }
}
