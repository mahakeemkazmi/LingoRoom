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

/**
 * Turns 16 kHz audio into the log-mel features expected by NVIDIA NeMo speech models (IndicConformer is one of them).
 * It follows NeMo's AudioToMelSpectrogramPreprocessor defaults: pre-emphasis 0.97, 25 ms Hann window,
 * 10 ms step, 512 point FFT, Slaney mel filters, natural log, and optional per-feature normalization.
 * Pure Java, with no Android dependency, so it can be tested on any machine.
 */
public class NemoFeatureExtractor {
    public static final int SAMPLE_RATE = 16000;
    private static final int N_FFT = 512;
    private static final int WIN_LENGTH = 400;
    private static final int HOP_LENGTH = 160;
    private static final float PREEMPHASIS = 0.97f;
    private static final double LOG_ZERO_GUARD = Math.pow(2, -24);

    private final int numMels;
    private final boolean perFeatureNormalization;
    private final double[] window = new double[N_FFT];   // Hann window of WIN_LENGTH, centred in N_FFT
    private final double[][] melFilters;                 // [numMels][N_FFT / 2 + 1]

    public NemoFeatureExtractor(int numMels, boolean perFeatureNormalization) {
        this.numMels = numMels;
        this.perFeatureNormalization = perFeatureNormalization;
        int offset = (N_FFT - WIN_LENGTH) / 2;
        for (int i = 0; i < WIN_LENGTH; i++) {
            window[offset + i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WIN_LENGTH - 1));
        }
        melFilters = createMelFilters(numMels);
    }

    public int getNumMels() {
        return numMels;
    }

    /**
     * @param samples mono audio at 16 kHz, values between -1 and 1
     * @return features as [numMels][frames]
     */
    public float[][] extract(float[] samples) {
        int n = samples.length;
        // pre-emphasis
        double[] signal = new double[n];
        if (n > 0) signal[0] = samples[0];
        for (int i = 1; i < n; i++) {
            signal[i] = samples[i] - PREEMPHASIS * samples[i - 1];
        }
        int frames = 1 + n / HOP_LENGTH;
        int pad = N_FFT / 2;
        int bins = N_FFT / 2 + 1;
        float[][] features = new float[numMels][frames];
        double[] real = new double[N_FFT];
        double[] imag = new double[N_FFT];
        double[] power = new double[bins];
        for (int t = 0; t < frames; t++) {
            int start = t * HOP_LENGTH - pad;
            for (int i = 0; i < N_FFT; i++) {
                real[i] = reflect(signal, start + i) * window[i];
                imag[i] = 0;
            }
            fft(real, imag);
            for (int k = 0; k < bins; k++) {
                power[k] = real[k] * real[k] + imag[k] * imag[k];
            }
            for (int m = 0; m < numMels; m++) {
                double sum = 0;
                double[] filter = melFilters[m];
                for (int k = 0; k < bins; k++) {
                    if (filter[k] != 0) sum += filter[k] * power[k];
                }
                features[m][t] = (float) Math.log(sum + LOG_ZERO_GUARD);
            }
        }
        if (perFeatureNormalization && frames > 1) {
            for (int m = 0; m < numMels; m++) {
                double mean = 0;
                for (int t = 0; t < frames; t++) mean += features[m][t];
                mean /= frames;
                double variance = 0;
                for (int t = 0; t < frames; t++) {
                    double d = features[m][t] - mean;
                    variance += d * d;
                }
                double std = Math.sqrt(variance / (frames - 1)) + 1e-5;
                for (int t = 0; t < frames; t++) {
                    features[m][t] = (float) ((features[m][t] - mean) / std);
                }
            }
        }
        return features;
    }

    /** Reads the signal with reflection at both ends, as torch.stft does with center=True. */
    private static double reflect(double[] signal, int index) {
        int n = signal.length;
        if (n == 0) return 0;
        if (n == 1) return signal[0];
        int period = 2 * (n - 1);
        int i = index % period;
        if (i < 0) i += period;
        if (i >= n) i = period - i;
        return signal[i];
    }

    /** In-place radix-2 FFT. The length must be a power of two. */
    static void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wLenR = Math.cos(angle), wLenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wR = 1, wI = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double uR = real[a], uI = imag[a];
                    double vR = real[b] * wR - imag[b] * wI;
                    double vI = real[b] * wI + imag[b] * wR;
                    real[a] = uR + vR; imag[a] = uI + vI;
                    real[b] = uR - vR; imag[b] = uI - vI;
                    double nextR = wR * wLenR - wI * wLenI;
                    wI = wR * wLenI + wI * wLenR;
                    wR = nextR;
                }
            }
        }
    }

    // Slaney mel scale and Slaney-normalized triangular filters, the same as librosa.filters.mel(htk=False)
    private static double hzToMel(double hz) {
        double fSp = 200.0 / 3;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logStep = Math.log(6.4) / 27.0;
        if (hz >= minLogHz) return minLogMel + Math.log(hz / minLogHz) / logStep;
        return hz / fSp;
    }

    private static double melToHz(double mel) {
        double fSp = 200.0 / 3;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logStep = Math.log(6.4) / 27.0;
        if (mel >= minLogMel) return minLogHz * Math.exp(logStep * (mel - minLogMel));
        return fSp * mel;
    }

    private static double[][] createMelFilters(int numMels) {
        int bins = N_FFT / 2 + 1;
        double melMin = hzToMel(0);
        double melMax = hzToMel(SAMPLE_RATE / 2.0);
        double[] hzPoints = new double[numMels + 2];
        for (int i = 0; i < numMels + 2; i++) {
            hzPoints[i] = melToHz(melMin + (melMax - melMin) * i / (numMels + 1));
        }
        double[][] filters = new double[numMels][bins];
        for (int m = 0; m < numMels; m++) {
            double lower = hzPoints[m], centre = hzPoints[m + 1], upper = hzPoints[m + 2];
            double norm = 2.0 / (upper - lower);
            for (int k = 0; k < bins; k++) {
                double freq = (double) k * SAMPLE_RATE / N_FFT;
                double up = (freq - lower) / (centre - lower);
                double down = (upper - freq) / (upper - centre);
                double weight = Math.max(0, Math.min(up, down));
                filters[m][k] = weight * norm;
            }
        }
        return filters;
    }
}
