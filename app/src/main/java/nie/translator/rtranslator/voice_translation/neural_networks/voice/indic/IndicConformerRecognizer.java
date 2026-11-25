/*
 * Copyright 2026 Exelere.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation.neural_networks.voice.indic;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Speech recognition with a NeMo CTC model exported to ONNX, such as AI4Bharat's IndicConformer.
 * One instance handles one language (one model file). It has no Android dependency.
 */
public class IndicConformerRecognizer implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final NemoFeatureExtractor featureExtractor;
    private final CtcTokenDecoder decoder;
    private final String featuresInputName;
    private final String lengthInputName;   // null if the model does not take the length

    public IndicConformerRecognizer(String modelPath, String tokensPath, int threads) throws OrtException, IOException {
        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(threads);
        session = environment.createSession(modelPath, options);
        try (FileInputStream tokens = new FileInputStream(tokensPath)) {
            decoder = new CtcTokenDecoder(tokens);
        }
        String features = null, length = null;
        int numMels = 80;
        for (Map.Entry<String, NodeInfo> input : session.getInputInfo().entrySet()) {
            if (input.getValue().getInfo() instanceof TensorInfo) {
                TensorInfo info = (TensorInfo) input.getValue().getInfo();
                if (info.type == OnnxJavaType.FLOAT) {
                    features = input.getKey();
                    long[] shape = info.getShape();
                    if (shape.length == 3 && shape[1] > 0) numMels = (int) shape[1];
                } else if (info.type == OnnxJavaType.INT64) {
                    length = input.getKey();
                }
            }
        }
        if (features == null) {
            throw new IOException("The model has no audio features input");
        }
        featuresInputName = features;
        lengthInputName = length;
        // NeMo models normalize each feature over time unless their metadata says otherwise
        String normalize = session.getMetadata().getCustomMetadata().get("normalize_type");
        boolean perFeature = normalize == null || normalize.isEmpty() || normalize.equals("per_feature");
        featureExtractor = new NemoFeatureExtractor(numMels, perFeature);
    }

    /**
     * @param samples mono audio at 16 kHz, values between -1 and 1
     * @return the recognized text (empty if nothing was recognized)
     */
    public String recognize(float[] samples) throws OrtException {
        float[][] features = featureExtractor.extract(samples);
        int numMels = features.length;
        int frames = features[0].length;
        FloatBuffer buffer = FloatBuffer.allocate(numMels * frames);
        for (float[] row : features) buffer.put(row);
        buffer.rewind();
        Map<String, OnnxTensor> inputs = new HashMap<>();
        OnnxTensor featuresTensor = OnnxTensor.createTensor(environment, buffer, new long[]{1, numMels, frames});
        inputs.put(featuresInputName, featuresTensor);
        OnnxTensor lengthTensor = null;
        if (lengthInputName != null) {
            lengthTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[]{frames}), new long[]{1});
            inputs.put(lengthInputName, lengthTensor);
        }
        try (OrtSession.Result result = session.run(inputs)) {
            float[][][] output = (float[][][]) result.get(0).getValue();   // [1][frames][vocabulary]
            float[][] scores = output[0];
            int[] best = new int[scores.length];
            for (int t = 0; t < scores.length; t++) {
                float[] frame = scores[t];
                int arg = 0;
                for (int v = 1; v < frame.length; v++) {
                    if (frame[v] > frame[arg]) arg = v;
                }
                best[t] = arg;
            }
            return decoder.decode(best);
        } finally {
            featuresTensor.close();
            if (lengthTensor != null) lengthTensor.close();
        }
    }

    /** @return a short description of the model's inputs, outputs and metadata, for diagnostics */
    public String describe() throws OrtException {
        return "inputs=" + session.getInputNames() + " outputs=" + session.getOutputNames()
                + " metadata=" + session.getMetadata().getCustomMetadata()
                + " vocabulary=" + decoder.getVocabularySize() + " blank=" + decoder.getBlankId();
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
