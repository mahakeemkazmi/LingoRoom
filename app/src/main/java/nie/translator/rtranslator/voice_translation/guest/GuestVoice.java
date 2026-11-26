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

package nie.translator.rtranslator.voice_translation.guest;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a phrase into a sound file with the voices of this phone, so that the phone of a guest can play it.
 * Browsers do not all have voices of their own, the host phone does.
 */
final class GuestVoice {
    interface Listener {
        void onVoiceReady(String key, byte[] wav);
    }

    private static final int MAX_WAV_BYTES = 3 * 1024 * 1024;
    private final ConcurrentHashMap<String, File> files = new ConcurrentHashMap<>();
    private final File directory;
    private final Listener listener;
    private final TextToSpeech tts;
    private volatile boolean ready;
    private int counter;

    GuestVoice(Context context, Listener voiceListener) {
        listener = voiceListener;
        directory = new File(context.getCacheDir(), "guest_voice");
        directory.mkdirs();
        tts = new TextToSpeech(context.getApplicationContext(), status -> ready = status == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                File file = files.remove(utteranceId);
                if (file == null) {
                    return;
                }
                try {
                    long length = file.length();
                    if (length > 44 && length <= MAX_WAV_BYTES) {
                        byte[] bytes = new byte[(int) length];
                        try (FileInputStream input = new FileInputStream(file)) {
                            int offset = 0;
                            while (offset < bytes.length) {
                                int read = input.read(bytes, offset, bytes.length - offset);
                                if (read < 0) break;
                                offset += read;
                            }
                        }
                        listener.onVoiceReady(utteranceId.substring(utteranceId.indexOf(':') + 1), bytes);
                    }
                } catch (Throwable ignored) {
                } finally {
                    file.delete();
                }
            }

            @Override
            public void onError(String utteranceId) {
                File file = files.remove(utteranceId);
                if (file != null) {
                    file.delete();
                }
            }
        });
    }

    /**
     * @param key the key of the message, given back to the listener with the sound
     */
    synchronized void synthesize(String key, String text, String languageCode) {
        if (!ready || text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            int result = tts.setLanguage(Locale.forLanguageTag(languageCode));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                return;
            }
            String id = (counter++) + ":" + key;
            File file = new File(directory, System.nanoTime() + ".wav");
            files.put(id, file);
            if (tts.synthesizeToFile(text, new Bundle(), file, id) != TextToSpeech.SUCCESS) {
                files.remove(id);
            }
        } catch (Throwable ignored) {
        }
    }

    void shutdown() {
        try {
            tts.shutdown();
        } catch (Throwable ignored) {
        }
    }
}
