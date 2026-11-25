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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;

/**
 * Experimental: listens to Urdu with AI4Bharat's IndicConformer instead of Whisper, when the user has enabled it
 * in the settings and the model has been downloaded. It also takes care of downloading the model files.
 */
public class IndicSpeechEngine implements Recognizer.AlternativeRecognizer {
    public static final String PREFERENCE_KEY = "indicSpeechUrdu";
    private static final String TAG = "indicSpeech";
    private static final String LANGUAGE = "ur";
    private static final String BASE_URL = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/";
    private static final long MIN_MODEL_BYTES = 100L * 1024 * 1024;   // the model is about 198 MB, anything much smaller is a broken download
    private static final long MIN_TOKENS_BYTES = 10L * 1024;
    private static final int THREADS = 4;

    private static IndicSpeechEngine instance;
    private static volatile boolean downloading = false;
    private static volatile int downloadPercent = 0;

    private final Context context;
    @Nullable
    private IndicConformerRecognizer recognizer;

    public interface DownloadListener {
        void onProgress(int percent);
        void onFinished(boolean success);
    }

    private IndicSpeechEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized IndicSpeechEngine getInstance(Context context) {
        if (instance == null) {
            instance = new IndicSpeechEngine(context);
        }
        return instance;
    }

    // files

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "indic_asr");
    }

    private static File tokensFile(Context context) {
        return new File(directory(context), "tokens.txt");
    }

    private static File modelFile(Context context) {
        return new File(new File(directory(context), LANGUAGE), "model.int8.onnx");
    }

    public static boolean isDownloaded(Context context) {
        return modelFile(context).length() >= MIN_MODEL_BYTES && tokensFile(context).length() >= MIN_TOKENS_BYTES;
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        return preferences.getBoolean(PREFERENCE_KEY, false) && isDownloaded(context);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("default", Context.MODE_PRIVATE).edit().putBoolean(PREFERENCE_KEY, enabled).apply();
        if (!enabled && instance != null) {
            instance.release();
        }
    }

    public static boolean isDownloading() {
        return downloading;
    }

    public static int getDownloadPercent() {
        return downloadPercent;
    }

    // recognition

    @Override
    public boolean supports(String languageCode) {
        return languageCode != null && languageCode.toLowerCase().startsWith(LANGUAGE) && isEnabled(context);
    }

    @Nullable
    @Override
    public synchronized String recognize(float[] samples, String languageCode) {
        try {
            if (recognizer == null) {
                long start = System.currentTimeMillis();
                recognizer = new IndicConformerRecognizer(modelFile(context).getPath(), tokensFile(context).getPath(), THREADS);
                Log.i(TAG, "model loaded in " + (System.currentTimeMillis() - start) + " ms: " + recognizer.describe());
            }
            long start = System.currentTimeMillis();
            String text = recognizer.recognize(samples);
            Log.i(TAG, "recognized " + (samples.length / 16000f) + " s of audio in " + (System.currentTimeMillis() - start) + " ms: " + text);
            return text;
        } catch (Throwable error) {
            // any failure (including running out of memory) falls back to the standard recognizer
            Log.e(TAG, "recognition failed, falling back", error);
            release();
            return null;
        }
    }

    public synchronized void release() {
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignored) {
            }
            recognizer = null;
        }
    }

    // download

    /**
     * Downloads the model files in the background. The listener is called from the download thread.
     * @return false if a download is already running
     */
    public static boolean download(Context context, @Nullable final DownloadListener listener) {
        final Context appContext = context.getApplicationContext();
        synchronized (IndicSpeechEngine.class) {
            if (downloading) {
                return false;
            }
            downloading = true;
            downloadPercent = 0;
        }
        new Thread("indicSpeechDownload") {
            @Override
            public void run() {
                boolean success = false;
                try {
                    File tokens = tokensFile(appContext);
                    File model = modelFile(appContext);
                    model.getParentFile().mkdirs();
                    if (tokens.length() < MIN_TOKENS_BYTES) {
                        downloadFile(BASE_URL + "tokens.txt", tokens, null);
                    }
                    if (model.length() < MIN_MODEL_BYTES) {
                        downloadFile(BASE_URL + LANGUAGE + "/model.int8.onnx", model, listener);
                    }
                    success = isDownloaded(appContext);
                } catch (Throwable error) {
                    Log.e(TAG, "download failed", error);
                } finally {
                    downloading = false;
                    if (listener != null) {
                        listener.onFinished(success);
                    }
                }
            }
        }.start();
        return true;
    }

    private static void downloadFile(String address, File destination, @Nullable DownloadListener listener) throws Exception {
        File partial = new File(destination.getPath() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new java.io.IOException("HTTP " + status + " for " + address);
            }
            long total = connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                long done = 0;
                int lastPercent = -1;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    done += read;
                    if (total > 0) {
                        int percent = (int) (done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            if (listener != null) {
                                downloadPercent = percent;
                                listener.onProgress(percent);
                            }
                        }
                    }
                }
            }
            if (total > 0 && partial.length() != total) {
                throw new java.io.IOException("incomplete download of " + address);
            }
            if (destination.exists() && !destination.delete()) {
                throw new java.io.IOException("cannot replace " + destination);
            }
            if (!partial.renameTo(destination)) {
                throw new java.io.IOException("cannot save " + destination);
            }
        } finally {
            connection.disconnect();
            if (partial.exists()) {
                partial.delete();
            }
        }
    }
}
