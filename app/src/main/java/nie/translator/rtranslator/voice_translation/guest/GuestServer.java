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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The state of a room (a conversation followed by guests in the browser of their own phones) and a very small
 * web server that serves it on the local network. Over the internet the same state travels through the relay.
 * Every message is kept in two languages, each guest reads and hears the one they chose. Pure Java.
 */
public final class GuestServer {
    public static final String HOST_ID = "host";

    public interface GuestListener {
        /** @param language the language the guest chose, id and name say who the guest is */
        void onGuestText(String text, String language, String id, String name);

        /** The guest wants to hear a message again, in their language. */
        void onGuestReplay(String key, String language);
    }

    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_BODY_BYTES = 4096;
    private static final int MAX_TEXT_CHARS = 500;
    private static final int MAX_MESSAGES = 300;
    private static final int MAX_SOUNDS = 16;

    private static final class Entry {
        String key;
        String from;
        String name;
        String original;
        String originalLanguage;
        String translated;
        String translatedLanguage;
        LinkedHashMap<String, String> extra;   // the message in the other languages of the room
        boolean isFinal;
        long revision;
    }

    private static final class Sound {
        byte[] wav;
        String language;
        String from;
        long revision;
    }

    private final String page;
    private final String token;
    private final LinkedHashMap<String, Entry> messages = new LinkedHashMap<>();
    private final LinkedHashMap<String, Sound> sounds = new LinkedHashMap<>();   // the last spoken phrases
    private final String[] englishLabels;
    private final LinkedHashMap<String, String[]> labels = new LinkedHashMap<>();   // the texts of the page, by language
    private String[] languages = {"", "", "", ""};   // code and name of each language a guest can choose, the two main ones first
    private final LinkedHashMap<String, long[]> presence = new LinkedHashMap<>();   // local guests: {time of the last sign of life}
    private final LinkedHashMap<String, String> presenceLanguage = new LinkedHashMap<>();
    private volatile String activeJson = "[]";
    private final ExecutorService workers = Executors.newFixedThreadPool(8);
    private ServerSocket serverSocket;
    private volatile boolean running;
    private volatile GuestListener guestListener;
    private volatile Runnable changeListener;
    private volatile long lastGuestContact;
    private volatile long lastGuestListening;
    private long revision;

    /**
     * @param page the html of the guest page
     * @param labels the texts of the page in English
     */
    public GuestServer(String page, String[] labels) {
        this.page = page;
        this.englishLabels = labels.clone();
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

    /** @return true if a guest page on the local network has asked for news in the last few seconds */
    public boolean isGuestConnected() {
        return System.currentTimeMillis() - lastGuestContact < 4000;
    }

    /** @return true if a guest page on the local network is playing the conversation aloud right now */
    public boolean isGuestListening() {
        return System.currentTimeMillis() - lastGuestListening < 4000;
    }

    public void setGuestListener(GuestListener listener) {
        guestListener = listener;
    }

    /** @param listener called every time the conversation, the languages or a text of the page changes */
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

    /**
     * Sets the languages the guests can choose from.
     * @param codesAndNames code and name of each language, the two main languages of the room first
     */
    public synchronized void setLanguages(String[] codesAndNames) {
        languages = codesAndNames.clone();
        notifyChanged();
    }

    /** @param codes the extra languages in use right now (the page stops offering others when the room has enough) */
    public synchronized void setActiveLanguages(java.util.Collection<String> codes) {
        StringBuilder json = new StringBuilder("[");
        for (String code : codes) {
            if (json.length() > 1) json.append(',');
            json.append(quote(code));
        }
        String value = json.append(']').toString();
        if (!value.equals(activeJson)) {
            activeJson = value;
            notifyChanged();
        }
    }

    /** @return the languages chosen by the guests on the local network who gave a sign of life in the last seconds */
    public synchronized java.util.ArrayList<String> getGuestLanguages() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, long[]> guest : presence.entrySet()) {
            String language = presenceLanguage.get(guest.getKey());
            if (now - guest.getValue()[0] < 15000 && language != null && !language.isEmpty() && !result.contains(language)) {
                result.add(language);
            }
        }
        return result;
    }

    private synchronized void seen(String id, String language) {
        if (id.isEmpty()) {
            return;
        }
        if (presence.size() > 200) {
            presence.clear();
            presenceLanguage.clear();
        }
        presence.put(id, new long[]{System.currentTimeMillis()});
        presenceLanguage.put(id, language);
    }

    /** Adds or updates a message in one more language of the room. */
    public synchronized void publishExtra(String key, String language, String text) {
        Entry entry = messages.get(key);
        if (entry == null || language == null || text == null) {
            return;
        }
        if (entry.extra == null) {
            entry.extra = new LinkedHashMap<>();
        }
        entry.extra.put(language, text);
        entry.revision = ++revision;
        notifyChanged();
    }

    /** Sets a text of the page in a language. Texts that are not set stay in English. */
    public synchronized void setLabel(String language, int index, String text) {
        if (language == null || index < 0 || index >= englishLabels.length || text == null || text.trim().isEmpty()) {
            return;
        }
        String[] translated = labels.get(language);
        if (translated == null) {
            translated = englishLabels.clone();
            labels.put(language, translated);
        }
        translated[index] = text.trim();
        notifyChanged();
    }

    public synchronized void clearLabels() {
        labels.clear();
        notifyChanged();
    }

    /**
     * Adds a message of the conversation, or updates it while its translation is still growing.
     *
     * @param from who said it: HOST_ID or the id of a guest
     */
    public synchronized void publish(String key, String from, String name, String original, String originalLanguage, String translated, String translatedLanguage, boolean isFinal) {
        Entry entry = messages.get(key);
        if (entry == null) {
            entry = new Entry();
            entry.key = key;
            messages.put(key, entry);
            if (messages.size() > MAX_MESSAGES) {
                messages.remove(messages.keySet().iterator().next());
            }
        }
        entry.from = from == null ? HOST_ID : from;
        entry.name = name == null ? "" : name;
        entry.original = original == null ? "" : original;
        entry.originalLanguage = originalLanguage == null ? "" : originalLanguage;
        entry.translated = translated == null ? "" : translated;
        entry.translatedLanguage = translatedLanguage == null ? "" : translatedLanguage;
        entry.isFinal = isFinal;
        entry.revision = ++revision;
        notifyChanged();
    }

    /** @return the text of a message in a language, or null */
    public synchronized String getText(String key, String language) {
        Entry entry = messages.get(key);
        if (entry == null || language == null) {
            return null;
        }
        if (language.equals(entry.originalLanguage)) {
            return entry.original;
        }
        if (language.equals(entry.translatedLanguage)) {
            return entry.translated;
        }
        return entry.extra == null ? null : entry.extra.get(language);
    }

    /**
     * Adds a sound (a wav file with one spoken phrase). The pages of the guests who chose that language play the
     * sounds in the order they were added, so a long translation is heard phrase by phrase.
     *
     * @param from who said the phrase (that person does not hear it again)
     */
    public synchronized void addSound(String name, byte[] wav, String language, String from) {
        if (wav == null) {
            return;
        }
        Sound sound = new Sound();
        sound.wav = wav;
        sound.language = language == null ? "" : language;
        sound.from = from == null ? "" : from;
        sound.revision = ++revision;
        sounds.put(name, sound);
        while (sounds.size() > MAX_SOUNDS) {
            sounds.remove(sounds.keySet().iterator().next());
        }
        notifyChanged();
    }

    private synchronized byte[] getSound(String name) {
        Sound sound = sounds.get(name);
        return sound == null ? null : sound.wav;
    }

    /** @return the state of the room as json, with only the messages and sounds changed after the given revision */
    public synchronized String eventsJson(long since) {
        StringBuilder json = new StringBuilder();
        json.append("{\"rev\":").append(revision);
        json.append(",\"langs\":[");
        for (int i = 0; i + 1 < languages.length; i += 2) {
            if (i > 0) json.append(',');
            json.append("{\"c\":").append(quote(languages[i])).append(",\"n\":").append(quote(languages[i + 1])).append('}');
        }
        json.append("],\"active\":").append(activeJson);
        json.append(",\"labels\":{\"en\":").append(array(englishLabels));
        for (Map.Entry<String, String[]> item : labels.entrySet()) {
            if (!item.getKey().equals("en")) {
                json.append(',').append(quote(item.getKey())).append(':').append(array(item.getValue()));
            }
        }
        json.append("},\"messages\":[");
        boolean first = true;
        for (Entry entry : messages.values()) {
            if (entry.revision <= since) continue;
            if (!first) json.append(',');
            first = false;
            json.append("{\"key\":").append(quote(entry.key));
            json.append(",\"from\":").append(quote(entry.from));
            json.append(",\"name\":").append(quote(entry.name));
            json.append(",\"original\":").append(quote(entry.original));
            json.append(",\"ol\":").append(quote(entry.originalLanguage));
            json.append(",\"translated\":").append(quote(entry.translated));
            json.append(",\"tl\":").append(quote(entry.translatedLanguage));
            if (entry.extra != null) {
                json.append(",\"x\":{");
                boolean firstExtra = true;
                for (Map.Entry<String, String> more : entry.extra.entrySet()) {
                    if (!firstExtra) json.append(',');
                    firstExtra = false;
                    json.append(quote(more.getKey())).append(':').append(quote(more.getValue()));
                }
                json.append('}');
            }
            json.append(",\"final\":").append(entry.isFinal).append('}');
        }
        json.append("],\"sounds\":[");
        first = true;
        for (Map.Entry<String, Sound> item : sounds.entrySet()) {
            if (item.getValue().revision <= since) continue;
            if (!first) json.append(',');
            first = false;
            json.append("{\"n\":").append(quote(item.getKey()));
            json.append(",\"l\":").append(quote(item.getValue().language));
            json.append(",\"f\":").append(quote(item.getValue().from)).append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static String array(String[] values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) json.append(',');
            json.append(quote(values[i]));
        }
        return json.append(']').toString();
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

    /** Makes a text received from a browser safe to show: one line, limited length. */
    static String clean(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > maxChars ? cleaned.substring(0, maxChars) : cleaned;
    }

    private static String parameter(String query, String name) {
        for (String part : query.split("&")) {
            if (part.startsWith(name + "=")) {
                try {
                    return URLDecoder.decode(part.substring(name.length() + 1), "UTF-8");
                } catch (Throwable error) {
                    return "";
                }
            }
        }
        return "";
    }

    private static String readBody(InputStream input, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        return new String(body, 0, offset, StandardCharsets.UTF_8);
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
            GuestListener listener = guestListener;
            if (method.equals("GET") && target.equals(base)) {
                respond(output, "200 OK", "text/html; charset=utf-8", page);
            } else if (method.equals("GET") && target.equals(base + "/events")) {
                lastGuestContact = System.currentTimeMillis();
                seen(clean(parameter(query, "id"), 40), clean(parameter(query, "lang"), 16));
                if (parameter(query, "aloud").equals("1")) {
                    lastGuestListening = System.currentTimeMillis();
                }
                long since = 0;
                try {
                    since = Long.parseLong(parameter(query, "since"));
                } catch (NumberFormatException ignored) {
                }
                respond(output, "200 OK", "application/json; charset=utf-8", eventsJson(since));
            } else if (method.equals("GET") && target.startsWith(base + "/audio/")) {
                byte[] wav = getSound(target.substring(base.length() + 7));
                if (wav == null) {
                    respond(output, "404 Not Found", "text/plain; charset=utf-8", "not found");
                } else {
                    String head = "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: " + wav.length
                            + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                    output.write(head.getBytes(StandardCharsets.ISO_8859_1));
                    output.write(wav);
                    output.flush();
                }
            } else if (method.equals("POST") && (target.equals(base + "/say") || target.equals(base + "/replay"))) {
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    respond(output, "413 Payload Too Large", "text/plain; charset=utf-8", "too long");
                    return;
                }
                String body = clean(readBody(input, contentLength), MAX_TEXT_CHARS);
                if (!body.isEmpty() && listener != null) {
                    String language = clean(parameter(query, "lang"), 16);
                    if (target.endsWith("/say")) {
                        listener.onGuestText(body, language, clean(parameter(query, "id"), 40), clean(parameter(query, "name"), 30));
                    } else {
                        listener.onGuestReplay(body, language);
                    }
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
