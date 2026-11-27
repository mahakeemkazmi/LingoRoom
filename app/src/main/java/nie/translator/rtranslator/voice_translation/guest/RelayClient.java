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
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Connection of the host phone to the guest relay on the internet (see the relay folder of the project).
 * The relay only forwards: what the guests write or say arrives here, and the conversation goes back.
 */
final class RelayClient {
    interface Listener {
        void onGuestJoined();

        void onGuestText(String text, String language, String id, String name);

        void onGuestReplay(String key, String language);

        /** @param samples what the guest said, mono, 16 kHz */
        void onGuestAudio(float[] samples, String language, String id, String name);
    }

    private static final int MAX_AUDIO_BYTES = 30 * 16000 * 2;   // 30 seconds
    private static final int MIN_AUDIO_BYTES = 16000 * 2 * 3 / 10;   // 0.3 seconds
    private static final int MAX_SPEAKERS = 30;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final String url;
    private final Listener listener;
    private final HashMap<String, ByteArrayOutputStream> audio = new HashMap<>();   // the voice of each guest who is talking
    private final HashMap<String, Long> listening = new HashMap<>();   // the guests who play the conversation aloud
    private WebSocket socket;
    private volatile boolean open;
    private volatile boolean closed;

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
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");
                    String id = GuestServer.clean(json.optString("id"), 40);
                    String language = GuestServer.clean(json.optString("lang"), 16);
                    String name = GuestServer.clean(json.optString("name"), 30);
                    if (type.equals("hello")) {
                        listener.onGuestJoined();
                    } else if (type.equals("aloud")) {
                        synchronized (listening) {
                            if (json.optBoolean("on")) {
                                if (listening.size() < 100) listening.put(id, System.currentTimeMillis());
                            } else {
                                listening.remove(id);
                            }
                        }
                    } else if (type.equals("replay")) {
                        listener.onGuestReplay(GuestServer.clean(json.optString("key"), 40), language);
                    } else if (type.equals("say")) {
                        String said = GuestServer.clean(json.optString("text"), 500);
                        if (!said.isEmpty()) {
                            listener.onGuestText(said, language, id, name);
                        }
                    } else if (type.equals("pcm")) {
                        byte[] chunk = Base64.decode(json.optString("d"), Base64.DEFAULT);
                        synchronized (audio) {
                            ByteArrayOutputStream buffer = audio.get(id);
                            if (buffer == null) {
                                if (audio.size() >= MAX_SPEAKERS) {
                                    return;
                                }
                                buffer = new ByteArrayOutputStream();
                                audio.put(id, buffer);
                            }
                            if (buffer.size() + chunk.length <= MAX_AUDIO_BYTES) {
                                buffer.write(chunk, 0, chunk.length);
                            }
                        }
                    } else if (type.equals("audio_end")) {
                        byte[] bytes;
                        synchronized (audio) {
                            ByteArrayOutputStream buffer = audio.remove(id);
                            bytes = buffer == null ? new byte[0] : buffer.toByteArray();
                        }
                        if (bytes.length >= MIN_AUDIO_BYTES) {
                            float[] samples = new float[bytes.length / 2];
                            for (int i = 0; i < samples.length; i++) {
                                samples[i] = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8)) / 32768f;
                            }
                            listener.onGuestAudio(samples, language, id, name);
                        }
                    }
                } catch (Throwable ignored) {
                    // a strange message from a browser must never disturb the conversation
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

    /** @return true if the page of at least one guest is playing the conversation aloud (pages tell us every few seconds) */
    boolean isGuestListening() {
        if (!open) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (listening) {
            for (Long time : listening.values()) {
                if (now - time < 12000) {
                    return true;
                }
            }
        }
        return false;
    }

    synchronized void send(String text) {
        if (socket != null && open) {
            socket.send(text);
        }
    }

    synchronized void send(byte[] bytes) {
        if (socket != null && open) {
            socket.send(ByteString.of(bytes));
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
