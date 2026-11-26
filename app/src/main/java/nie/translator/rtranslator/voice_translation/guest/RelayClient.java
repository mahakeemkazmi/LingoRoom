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

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Connection of the host phone to the guest relay on the internet (see the relay folder of the project).
 * The relay only forwards: what the guest writes or says arrives here, and the conversation goes back.
 */
final class RelayClient {
    interface Listener {
        void onGuestJoined();

        void onGuestText(String text);

        /** @param samples what the guest said, mono, 16 kHz */
        void onGuestAudio(float[] samples);
    }

    private static final int MAX_AUDIO_BYTES = 30 * 16000 * 2;   // 30 seconds
    private static final int MIN_AUDIO_BYTES = 16000 * 2 * 3 / 10;   // 0.3 seconds
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final String url;
    private final Listener listener;
    private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    private WebSocket socket;
    private volatile boolean open;
    private volatile boolean closed;
    private volatile long lastGuestContact;
    private volatile long lastGuestListening;

    RelayClient(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    synchronized void connect() {
        if (closed) {
            return;
        }
        socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                open = true;
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                lastGuestContact = System.currentTimeMillis();
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");
                    if (type.equals("hello")) {
                        listener.onGuestJoined();
                    } else if (type.equals("aloud")) {
                        lastGuestListening = json.optBoolean("on") ? System.currentTimeMillis() : 0;
                    } else if (type.equals("say")) {
                        String said = json.optString("text").replaceAll("\\s+", " ").trim();
                        if (said.length() > 500) {
                            said = said.substring(0, 500);
                        }
                        if (!said.isEmpty()) {
                            listener.onGuestText(said);
                        }
                    } else if (type.equals("audio_end")) {
                        byte[] bytes;
                        synchronized (audio) {
                            bytes = audio.toByteArray();
                            audio.reset();
                        }
                        if (bytes.length >= MIN_AUDIO_BYTES) {
                            float[] samples = new float[bytes.length / 2];
                            for (int i = 0; i < samples.length; i++) {
                                samples[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8)) / 32768f;
                            }
                            listener.onGuestAudio(samples);
                        }
                    }
                } catch (Throwable ignored) {
                    // a strange message from a browser must never disturb the conversation
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                lastGuestContact = System.currentTimeMillis();
                synchronized (audio) {
                    if (audio.size() + bytes.size() <= MAX_AUDIO_BYTES) {
                        byte[] chunk = bytes.toByteArray();
                        audio.write(chunk, 0, chunk.length);
                    }
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                retry();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                retry();
            }
        });
    }

    private void retry() {
        open = false;
        if (!closed) {
            handler.postDelayed(this::connect, 2500);
        }
    }

    boolean isOpen() {
        return open;
    }

    boolean isGuestConnected() {
        return System.currentTimeMillis() - lastGuestContact < 5 * 60 * 1000;
    }

    /** @return true if the page of the guest is reading the conversation aloud (it tells us every few seconds) */
    boolean isGuestListening() {
        return open && System.currentTimeMillis() - lastGuestListening < 12000;
    }

    synchronized void send(String text) {
        if (socket != null && open) {
            socket.send(text);
        }
    }

    synchronized void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (socket != null) {
            try {
                socket.send("{\"type\":\"bye\"}");
                socket.close(1000, "ended");
            } catch (Throwable ignored) {
            }
        }
        open = false;
    }
}
