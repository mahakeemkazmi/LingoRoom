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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A very small web server that runs on the host phone for one guest conversation.
 * A guest on the same Wi-Fi (or on the host's hotspot) opens the page in a browser, reads the conversation
 * in their language and can write back. Nothing leaves the local network. Pure Java, no Android classes.
 */
public final class GuestServer {
    public interface TextListener {
        void onGuestText(String text);
    }

    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 4096;
    private static final int MAX_TEXT_CHARS = 500;
    private static final int MAX_MESSAGES = 300;

    private static final class Entry {
        String key;
        boolean host;
        String original;
        String translated;
        boolean isFinal;
        boolean hasAudio;
        long revision;
    }

    private final String page;
    private final String token;
    private final LinkedHashMap<String, Entry> messages = new LinkedHashMap<>();
    private final String[] labels;
    private final LinkedHashMap<String, byte[]> audio = new LinkedHashMap<>();   // the spoken form of the last messages
    private final ExecutorService workers = Executors.newFixedThreadPool(6);
    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile TextListener textListener;
    private volatile Runnable changeListener;
    private volatile String speechLanguage = "";
    private volatile long lastGuestContact;
    private volatile long lastGuestListening;
    private long revision;

    /**
     * @param page the html of the guest page
     * @param labels the texts of the page, in English until they are replaced with setLabel
     */
    public GuestServer(String page, String[] labels) {
        this.page = page;
        this.labels = labels.clone();
        byte[] random = new byte[9];
        new SecureRandom().nextBytes(random);
        StringBuilder builder = new StringBuilder();
        for (byte value : random) {
            builder.append(Integer.toHexString((value & 0xFF) | 0x100).substring(1));
        }
        token = builder.toString();
    }

    /** Starts listening on the first free port of a small range. */
    public synchronized void start() throws IOException {
        IOException lastError = null;
        for (int port = 8765; port < 8775; port++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
                serverSocket = socket;
                break;
            } catch (IOException error) {
                lastError = error;
            }
        }
        if (serverSocket == null) {
            throw lastError != null ? lastError : new IOException("no free port");
        }
        running = true;
        final ServerSocket socket = serverSocket;
        Thread acceptThread = new Thread("guestServer") {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket client = socket.accept();
                        workers.execute(() -> handle(client));
                    } catch (Throwable error) {
                        if (!running) {
                            return;
                        }
                    }
                }
            }
        };
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    /** @return the path of the guest page, it contains the secret of this session */
    public String getPath() {
        return "/g/" + token;
    }

    /** @return true if a guest page has asked for news in the last few seconds */
    public boolean isGuestConnected() {
        return System.currentTimeMillis() - lastGuestContact < 4000;
    }

    /** @param listener called every time the conversation, the language or a text of the page changes */
    public void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private void notifyChanged() {
        Runnable listener = changeListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized long getRevision() {
        return revision;
    }

    /** @return true if a guest page on the local network is reading the conversation aloud right now */
    public boolean isGuestListening() {
        return System.currentTimeMillis() - lastGuestListening < 4000;
    }

    public void setTextListener(TextListener listener) {
        textListener = listener;
    }

    /** @param languageCode the language the guest reads, used by the page to read aloud */
    public void setSpeechLanguage(String languageCode) {
        speechLanguage = languageCode == null ? "" : languageCode;
        notifyChanged();
    }

    public synchronized void setLabel(int index, String text) {
        if (index >= 0 && index < labels.length && text != null && !text.trim().isEmpty()) {
            labels[index] = text.trim();
            notifyChanged();
        }
    }

    /**
     * Adds a message of the conversation, or updates it while its translation is still growing.
     *
     * @param host true if the host of the conversation said it, false if the guest did
     */
    public synchronized void publish(String key, boolean host, String original, String translated, boolean isFinal) {
        Entry entry = messages.get(key);
        if (entry == null) {
            entry = new Entry();
            entry.key = key;
            messages.put(key, entry);
            if (messages.size() > MAX_MESSAGES) {
                String oldest = messages.keySet().iterator().next();
                messages.remove(oldest);
            }
        }
        entry.host = host;
        entry.original = original == null ? "" : original;
        entry.translated = translated == null ? "" : translated;
        entry.isFinal = isFinal;
        entry.revision = ++revision;
        notifyChanged();
    }

    /** Adds the spoken form (a wav file) of a message, the page of the guest plays it. */
    public synchronized void setAudio(String key, byte[] wav) {
        Entry entry = messages.get(key);
        if (entry == null || wav == null) {
            return;
        }
        audio.put(key, wav);
        while (audio.size() > 6) {
            audio.remove(audio.keySet().iterator().next());
        }
        entry.hasAudio = true;
        entry.revision = ++revision;
        notifyChanged();
    }

    private synchronized byte[] getAudio(String key) {
        return audio.get(key);
    }

    /** @return the state of the conversation as json, with only the messages changed after the given revision */
    public synchronized String eventsJson(long since) {
        StringBuilder json = new StringBuilder();
        json.append("{\"rev\":").append(revision);
        json.append(",\"lang\":").append(quote(speechLanguage));
        json.append(",\"labels\":[");
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) json.append(',');
            json.append(quote(labels[i]));
        }
        json.append("],\"messages\":[");
        boolean first = true;
        for (Map.Entry<String, Entry> item : messages.entrySet()) {
            Entry entry = item.getValue();
            if (entry.revision <= since) continue;
            if (!first) json.append(',');
            first = false;
            json.append("{\"key\":").append(quote(entry.key));
            json.append(",\"host\":").append(entry.host);
            json.append(",\"original\":").append(quote(entry.original));
            json.append(",\"translated\":").append(quote(entry.translated));
            json.append(",\"audio\":").append(entry.hasAudio);
            json.append(",\"final\":").append(entry.isFinal).append('}');
        }
        json.append("]}");
        return json.toString();
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                out.append("\\u").append(Integer.toHexString(c | 0x10000).substring(1));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(8000);
            InputStream input = socket.getInputStream();
            // request line and headers
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4 && headerBytes.size() < MAX_HEADER_BYTES) {
                int value = input.read();
                if (value < 0) break;
                headerBytes.write(value);
                if ((matched % 2 == 0 && value == 13) || (matched % 2 == 1 && value == 10)) {
                    matched++;
                } else {
                    matched = value == 13 ? 1 : 0;
                }
            }
            if (matched < 4) {
                return;
            }
            String[] headerLines = new String(headerBytes.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
            String[] requestLine = headerLines[0].split(" ");
            if (requestLine.length < 2) {
                return;
            }
            String method = requestLine[0];
            String target = requestLine[1];
            int contentLength = 0;
            for (int i = 1; i < headerLines.length; i++) {
                String line = headerLines[i];
                if (line.regionMatches(true, 0, "content-length:", 0, 15)) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String query = "";
            int questionMark = target.indexOf('?');
            if (questionMark >= 0) {
                query = target.substring(questionMark + 1);
                target = target.substring(0, questionMark);
            }
            OutputStream output = socket.getOutputStream();
            String base = getPath();
            if (method.equals("GET") && target.equals(base)) {
                respond(output, "200 OK", "text/html; charset=utf-8", page);
            } else if (method.equals("GET") && target.equals(base + "/events")) {
                lastGuestContact = System.currentTimeMillis();
                long since = 0;
                for (String part : query.split("&")) {
                    if (part.equals("aloud=1")) {
                        lastGuestListening = System.currentTimeMillis();
                    }
                    if (part.startsWith("since=")) {
                        try {
                            since = Long.parseLong(part.substring(6));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                respond(output, "200 OK", "application/json; charset=utf-8", eventsJson(since));
            } else if (method.equals("GET") && target.startsWith(base + "/audio/")) {
                byte[] wav = getAudio(target.substring(base.length() + 7));
                if (wav == null) {
                    respond(output, "404 Not Found", "text/plain; charset=utf-8", "not found");
                } else {
                    String head = "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: " + wav.length
                            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                    output.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    output.write(wav);
                    output.flush();
                }
            } else if (method.equals("POST") && target.equals(base + "/say")) {
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    respond(output, "413 Payload Too Large", "text/plain; charset=utf-8", "too long");
                    return;
                }
                byte[] body = new byte[contentLength];
                int offset = 0;
                while (offset < contentLength) {
                    int read = input.read(body, offset, contentLength - offset);
                    if (read < 0) break;
                    offset += read;
                }
                String text = new String(body, 0, offset, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
                if (text.length() > MAX_TEXT_CHARS) {
                    text = text.substring(0, MAX_TEXT_CHARS);
                }
                TextListener listener = textListener;
                if (!text.isEmpty() && listener != null) {
                    listener.onGuestText(text);
                }
                respond(output, "200 OK", "text/plain; charset=utf-8", "ok");
            } else {
                respond(output, "404 Not Found", "text/plain; charset=utf-8", "not found");
            }
        } catch (Throwable ignored) {
            // a broken request from a browser must never disturb the conversation
        }
    }

    private static void respond(OutputStream output, String status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
                + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.ISO_8859_1));
        output.write(bytes);
        output.flush();
    }

}
