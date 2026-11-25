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
import java.util.ArrayList;
import java.util.LinkedHashMap;

import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;

/**
 * Listens to Indian languages with AI4Bharat's IndicConformer instead of Whisper, for each language the user has
 * enabled in the settings and whose model has been downloaded. It also takes care of downloading the model files.
 */
public class IndicSpeechEngine implements Recognizer.AlternativeRecognizer {
    /** The languages that can use IndicConformer (each one has its own model file of about 200 MB). */
    public static final String[] LANGUAGES = {"ur", "hi", "te", "ta"};
    private static final String TAG = "indicSpeech";
    private static final String BASE_URL = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/";
    private static final long MIN_MODEL_BYTES = 100L * 1024 * 1024;   // a model is about 198 MB, anything much smaller is a broken download
    private static final long MIN_TOKENS_BYTES = 10L * 1024;
    private static final int THREADS = 4;
    private static final int MAX_LOADED_MODELS = 2;   // a conversation needs at most two languages, and each loaded model uses a lot of memory

    private static IndicSpeechEngine instance;
    private static volatile String downloadingLanguage = null;
    private static volatile int downloadPercent = 0;

    private final Context context;
    // loaded models, the most recently used last
    private final LinkedHashMap<String, IndicConformerRecognizer> recognizers = new LinkedHashMap<>(4, 0.75f, true);

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

    // languages and settings

    /** @return the supported language this code belongs to ("ur" for "ur" or "ur-PK"), or null */
    @Nullable
    public static String languageOf(@Nullable String languageCode) {
        if (languageCode == null) return null;
        String code = languageCode.toLowerCase();
        for (String language : LANGUAGES) {
            if (code.equals(language) || code.startsWith(language + "-") || code.startsWith(language + "_")) {
                return language;
            }
        }
        return null;
    }

    /** @return the key under which the setting of this language is stored */
    public static String preferenceKey(String language) {
        switch (language) {
            case "ur": return "indicSpeechUrdu";
            case "hi": return "indicSpeechHindi";
            case "te": return "indicSpeechTelugu";
            case "ta": return "indicSpeechTamil";
            default: return "indicSpeech_" + language;
        }
    }

    /** @return the language whose setting is stored under this key, or null */
    @Nullable
    public static String languageOfPreferenceKey(@Nullable String key) {
        for (String language : LANGUAGES) {
            if (preferenceKey(language).equals(key)) return language;
        }
        return null;
    }

    // files

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "indic_asr");
    }

    private static File tokensFile(Context context) {
        return new File(directory(context), "tokens.txt");
    }

    private static File modelFile(Context context, String language) {
        return new File(new File(directory(context), language), "model.int8.onnx");
    }

    public static boolean isDownloaded(Context context, String language) {
        return modelFile(context, language).length() >= MIN_MODEL_BYTES && tokensFile(context).length() >= MIN_TOKENS_BYTES;
    }

    public static boolean isEnabled(Context context, String language) {
        SharedPreferences preferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        return preferences.getBoolean(preferenceKey(language), false) && isDownloaded(context, language);
    }

    public static void setEnabled(Context context, String language, boolean enabled) {
        context.getSharedPreferences("default", Context.MODE_PRIVATE).edit().putBoolean(preferenceKey(language), enabled).apply();
        if (!enabled && instance != null) {
            instance.release(language);
        }
    }

    /** @return the language being downloaded right now, or null */
    @Nullable
    public static String getDownloadingLanguage() {
        return downloadingLanguage;
    }

    public static int getDownloadPercent() {
        return downloadPercent;
    }

    // recognition

    @Override
    public boolean supports(String languageCode) {
        String language = languageOf(languageCode);
        return language != null && isEnabled(context, language);
    }

    @Nullable
    @Override
    public synchronized String recognize(float[] samples, String languageCode) {
        String language = languageOf(languageCode);
        if (language == null) {
            return null;
        }
        try {
            IndicConformerRecognizer recognizer = recognizers.get(language);
            if (recognizer == null) {
                // we make room first, so that two models are never loaded on top of a third
                while (recognizers.size() >= MAX_LOADED_MODELS) {
                    String oldest = recognizers.keySet().iterator().next();
                    release(oldest);
                }
                long start = System.currentTimeMillis();
                recognizer = new IndicConformerRecognizer(modelFile(context, language).getPath(), tokensFile(context).getPath(), THREADS);
                recognizers.put(language, recognizer);
                Log.i(TAG, language + " model loaded in " + (System.currentTimeMillis() - start) + " ms: " + recognizer.describe());
            }
            long start = System.currentTimeMillis();
            String text = recognizer.recognize(samples);
            Log.i(TAG, "recognized " + (samples.length / 16000f) + " s of " + language + " audio in " + (System.currentTimeMillis() - start) + " ms: " + text);
            return text;
        } catch (Throwable error) {
            // any failure (including running out of memory) falls back to the standard recognizer
            Log.e(TAG, "recognition failed, falling back", error);
            releaseAll();
            return null;
        }
    }

    public synchronized void release(String language) {
        IndicConformerRecognizer recognizer = recognizers.remove(language);
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void releaseAll() {
        for (String language : new ArrayList<>(recognizers.keySet())) {
            release(language);
        }
    }

    // download

    /**
     * Downloads the model files of a language in the background. The listener is called from the download thread.
     * @return false if a download is already running (only one runs at a time)
     */
    public static boolean download(Context context, final String language, @Nullable final DownloadListener listener) {
        final Context appContext = context.getApplicationContext();
        synchronized (IndicSpeechEngine.class) {
            if (downloadingLanguage != null) {
                return false;
            }
            downloadingLanguage = language;
            downloadPercent = 0;
        }
        new Thread("indicSpeechDownload") {
            @Override
            public void run() {
                boolean success = false;
                try {
                    File tokens = tokensFile(appContext);
                    File model = modelFile(appContext, language);
                    model.getParentFile().mkdirs();
                    if (tokens.length() < MIN_TOKENS_BYTES) {
                        downloadFile(BASE_URL + "tokens.txt", tokens, null);
                    }
                    if (model.length() < MIN_MODEL_BYTES) {
                        downloadFile(BASE_URL + language + "/model.int8.onnx", model, listener);
                    }
                    success = isDownloaded(appContext, language);
                } catch (Throwable error) {
                    Log.e(TAG, "download failed", error);
                } finally {
                    downloadingLanguage = null;
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
