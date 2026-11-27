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

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import nie.translator.rtranslator.R;

/**
 * The room that is currently open, if any: a conversation that guests follow in the browser of their own
 * phones, each in the language they chose. The Conversation screen opens it, the Conversation service feeds it
 * and receives what the guests write and say.
 */
public final class GuestSession {
    /** Texts of the guest page. The service translates them into the languages of the room. */
    public static final String[] LABELS = {
            "Write your message here",
            "Send",
            "Read aloud",
            "To speak, tap the box below, then the microphone on your keyboard",
            "Hold the microphone to speak",
            "Tap here to hear the conversation",
            "Your name",
            "Choose your language",
            "Other language",
            "Waiting for the host to open the room"
    };

    /** A room handles this many languages besides its two main ones. Each one costs the host phone a translation per message. */
    public static final int MAX_EXTRA_LANGUAGES = 2;
    private static final String PREFERENCE_ROOM = "guestRoomId";
    private static final String PREFERENCE_ROOM_KEY = "guestRoomKey";
    private static final String PREFERENCE_PIN = "guestRoomPin";
    private static final java.util.ArrayList<String> extraLanguages = new java.util.ArrayList<>();
    private static final java.util.ArrayList<String> chosenLanguages = new java.util.ArrayList<>();

    /** @return true if a guest was using the language the last time the languages of the room were worked out */
    public static synchronized boolean isChosen(String code) {
        return chosenLanguages.contains(code);
    }

    public interface Host {
        void onGuestText(String text, String language, String id, String name);

        /** @param samples what the guest said, mono, 16 kHz */
        void onGuestAudio(float[] samples, String language, String id, String name);

        void onGuestSessionStarted();
    }

    private static GuestServer server;
    private static volatile RelayClient relay;
    private static volatile long relaySentRevision;
    private static String relayRoom;
    private static GuestVoice voice;
    private static int soundCounter;
    private static volatile boolean sameRoom = true;
    private static final ConcurrentHashMap<String, String[]> soundInfo = new ConcurrentHashMap<>();   // language and speaker of the sounds being made
    private static Host host;

    private GuestSession() {
    }

    /** Called by the Conversation service while it is alive. */
    public static synchronized void setHost(@Nullable Host newHost) {
        host = newHost;
    }

    /** @return true if this build of the app knows a guest relay, so guests can join over the internet */
    public static boolean hasRelay(Context context) {
        return !relayAddress(context).isEmpty();
    }

    private static volatile String verifiedAddress = "";

    private static String clean(String address) {
        address = address.trim();
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        return address.startsWith("https://") ? address : "";
    }

    /**
     * @return the address of the relay: our own domain once we have seen that it answers as the relay, and until
     * then the address the relay always has
     */
    private static String relayAddress(Context context) {
        String verified = verifiedAddress;
        return verified.isEmpty() ? clean(context.getString(R.string.guest_relay_url)) : verified;
    }

    /**
     * Checks in the background whether the relay answers on our own domain, to use that nicer address for the links.
     */
    public static void findBestAddress(Context context) {
        final String preferred = clean(context.getString(R.string.guest_relay_preferred_url));
        if (preferred.isEmpty() || !verifiedAddress.isEmpty()) {
            return;
        }
        new Thread("relayAddress") {
            @Override
            public void run() {
                if ("Lingo Room guest relay".equals(get(preferred + "/"))) {
                    verifiedAddress = preferred;
                }
            }
        }.start();
    }

    /** @return the text answered by an address of the relay, or null. Must not be called from the main thread. */
    @Nullable
    private static String get(String address) {
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[512];
                int length = 0;
                int read;
                while (length < buffer.length && (read = input.read(buffer, length, buffer.length - length)) > 0) {
                    length += read;
                }
                return new String(buffer, 0, length, StandardCharsets.UTF_8).trim();
            }
        } catch (Throwable error) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static final String NAME_LETTERS = "abcdefghjkmnpqrstuvwxyz23456789";   // none that look alike

    private static String randomName() {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            builder.append(NAME_LETTERS.charAt(random.nextInt(NAME_LETTERS.length())));
        }
        return builder.toString();
    }

    private static String randomPin() {
        return String.valueOf(1000 + new SecureRandom().nextInt(9000));
    }

    /** @return true if the text can be the name of a room: 3 to 24 small letters, digits or hyphens */
    public static boolean isValidRoomName(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9-]{2,23}") && !name.equals("www");
    }

    /** @return the name of the room of this phone (the end of its link), a new random one the first time */
    public static synchronized String getRoomName(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        String name = preferences.getString(PREFERENCE_ROOM, "");
        String key = preferences.getString(PREFERENCE_ROOM_KEY, "");
        if (!isValidRoomName(name) || name.length() == 32 || key.length() != 32) {
            // first time, or the long code of an older version: the room gets a short name
            name = randomName();
            preferences.edit().putString(PREFERENCE_ROOM, name).putString(PREFERENCE_ROOM_KEY, randomHex(16)).putString(PREFERENCE_PIN, randomPin()).apply();
        }
        return name;
    }

    /** @return the PIN guests need to enter the room of this phone */
    public static synchronized String getPin(Context context) {
        getRoomName(context);
        android.content.SharedPreferences preferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        String pin = preferences.getString(PREFERENCE_PIN, "");
        if (!pin.matches("[0-9]{4,8}")) {
            pin = randomPin();
            preferences.edit().putString(PREFERENCE_PIN, pin).apply();
        }
        return pin;
    }

    /**
     * Gives the room of this phone a name chosen by its user. Must not be called from the main thread.
     * @return 0 if the room now has that name, 1 if somebody else already has it, 2 if it could not be checked
     */
    public static int renameRoom(Context context, String name) {
        String address = relayAddress(context);
        if (address.isEmpty() || !isValidRoomName(name)) {
            return 2;
        }
        android.content.SharedPreferences preferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        getRoomName(context);
        String key = preferences.getString(PREFERENCE_ROOM_KEY, "");
        String answer = get(address + "/" + name + "/owner?key=" + key);
        if (answer == null) {
            return 2;
        }
        if (answer.contains("\"taken\"")) {
            return 1;
        }
        synchronized (GuestSession.class) {
            if (relay != null) {
                relay.close();
                relay = null;
            }
            preferences.edit().putString(PREFERENCE_ROOM, name).apply();
        }
        return 0;
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        StringBuilder builder = new StringBuilder();
        for (byte value : random) {
            builder.append(Integer.toHexString((value & 0xFF) | 0x100).substring(1));
        }
        return builder.toString();
    }

    private static Host currentHost() {
        synchronized (GuestSession.class) {
            return host;
        }
    }

    private static void ensureServer(Context context) throws Exception {
        if (server != null) {
            return;
        }
        final GuestServer created = new GuestServer(readPage(context), LABELS);
        created.setGuestListener(new GuestServer.GuestListener() {
            @Override
            public void onGuestText(String text, String language, String id, String name) {
                Host current = currentHost();
                if (current != null) {
                    current.onGuestText(text, language, id, name);
                }
            }

            @Override
            public void onGuestReplay(String key, String language) {
                replay(key, language);
            }
        });
        created.setChangeListener(() -> {
            RelayClient current = relay;
            if (current != null && current.isOpen()) {
                long sent = relaySentRevision;
                relaySentRevision = created.getRevision();
                current.send(created.eventsJson(sent));
            }
        });
        server = created;
        voice = new GuestVoice(context, (name, wav) -> {
            String[] info = soundInfo.remove(name);
            String language = info == null ? "" : info[0];
            String from = info == null ? "" : info[1];
            created.addSound(name, wav, language, from);
            RelayClient current = relay;
            if (current != null && current.isOpen()) {
                // over the internet the sound travels with the conversation: first its description, then the sound itself
                current.send("{\"type\":\"audio\",\"key\":" + GuestServer.quote(name) + ",\"lang\":" + GuestServer.quote(language)
                        + ",\"from\":" + GuestServer.quote(from) + "}");
                current.send(wav);
            }
        });
        if (host != null) {
            host.onGuestSessionStarted();
        }
    }

    /**
     * Opens the room over the internet, through the guest relay.
     *
     * @return the address of the guest page, or null if this build has no relay
     */
    @Nullable
    public static synchronized String startOnline(Context context) throws Exception {
        String address = relayAddress(context);
        if (address.isEmpty()) {
            return null;
        }
        boolean isNew = server == null;
        ensureServer(context);
        if (relay == null) {
            if (isNew) {
                sameRoom = false;   // guests who join by link are usually somewhere else
            }
            // the room of this phone keeps the same link, so it can be put in a calendar invitation
            relayRoom = getRoomName(context);
            String roomKey = context.getSharedPreferences("default", Context.MODE_PRIVATE).getString(PREFERENCE_ROOM_KEY, "");
            String roomPin = getPin(context);
            final GuestServer current = server;
            relay = new RelayClient("wss://" + address.substring(8) + "/" + relayRoom + "/ws?role=host&key=" + roomKey + "&pin=" + roomPin, new RelayClient.Listener() {
                @Override
                public void onGuestJoined() {
                    RelayClient client = relay;
                    if (client != null) {
                        relaySentRevision = current.getRevision();
                        client.send(current.eventsJson(0));
                    }
                }

                @Override
                public void onGuestText(String text, String language, String id, String name) {
                    Host target = currentHost();
                    if (target != null) {
                        target.onGuestText(text, language, id, name);
                    }
                }

                @Override
                public void onGuestReplay(String key, String language) {
                    replay(key, language);
                }

                @Override
                public void onGuestAudio(float[] samples, String language, String id, String name) {
                    Host target = currentHost();
                    if (target != null) {
                        target.onGuestAudio(samples, language, id, name);
                    }
                }
            });
            relay.connect();
        }
        return address + "/" + relayRoom;
    }

    /**
     * Opens the room on the local network, with no internet.
     *
     * @return the address of the guest page, or null if the phone is not on a Wi-Fi or hotspot
     */
    @Nullable
    public static synchronized String start(Context context) throws Exception {
        String address = localAddress();
        if (address == null) {
            return null;
        }
        boolean isNew = server == null;
        ensureServer(context);
        if (isNew) {
            sameRoom = true;   // guests on the same Wi-Fi are usually in the same room
        }
        if (!server.isRunning()) {
            server.start();
        }
        return "http://" + address + ":" + server.getPort() + server.getPath();
    }

    public static synchronized void stop() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (voice != null) {
            voice.shutdown();
            voice = null;
        }
        soundInfo.clear();
        GuestHotspot.stop();
    }

    /** Closes the room and gives this phone a new link. The old link stops working. */
    public static synchronized void changeLink(Context context) {
        stop();
        context.getSharedPreferences("default", Context.MODE_PRIVATE).edit().remove(PREFERENCE_ROOM).remove(PREFERENCE_ROOM_KEY).remove(PREFERENCE_PIN).apply();
    }

    public static synchronized boolean isActive() {
        return server != null;
    }

    /**
     * @return true if the guests are in the same room as this phone (or their voices reach it through a loudspeaker):
     * they hear its user directly and its mic hears them
     */
    public static boolean isSameRoom() {
        return sameRoom;
    }

    public static void setSameRoom(boolean value) {
        sameRoom = value;
    }

    /**
     * @return true if the phone of a guest is playing the conversation aloud: this phone then does not need
     * to speak the translation of what its own user says
     */
    public static boolean isGuestListening() {
        GuestServer currentServer;
        RelayClient currentRelay;
        synchronized (GuestSession.class) {
            currentServer = server;
            currentRelay = relay;
        }
        return (currentServer != null && currentServer.isGuestListening()) || (currentRelay != null && currentRelay.isGuestListening());
    }

    /**
     * @return true if guests who are somewhere else are listening. The mic of this phone then only ever hears its own user.
     */
    public static boolean isRemoteGuestListening() {
        RelayClient currentRelay = relay;
        return !sameRoom && currentRelay != null && currentRelay.isGuestListening();
    }

    /**
     * @param from who said it: GuestServer.HOST_ID or the id of a guest
     */
    public static synchronized void publish(String key, String from, String name, String original, String originalLanguage, String translated, String translatedLanguage, boolean isFinal) {
        if (server != null) {
            server.publish(key, from, name, original, originalLanguage, translated, translatedLanguage, isFinal);
        }
    }

    /**
     * Makes the phones of the guests who chose a language say a phrase. Phrases are heard in the order of the calls.
     *
     * @param from who said it (that person does not hear it again)
     */
    public static synchronized void speak(String phrase, String language, String from) {
        if (voice != null && language != null && !language.isEmpty()) {
            String name = "s" + (soundCounter++);
            soundInfo.put(name, new String[]{language, from == null ? "" : from});
            voice.synthesize(name, phrase, language);
        }
    }

    /** A guest tapped a message to hear it again. */
    private static synchronized void replay(String key, String language) {
        if (server != null && key != null) {
            String text = server.getText(key, language);
            if (text != null && !text.isEmpty()) {
                speak(text, language, "");
            }
        }
    }

    /**
     * Sets the languages the guests can choose from.
     * @param codesAndNames code and name of each language, the two main languages of the room first
     */
    public static synchronized void setLanguages(String[] codesAndNames) {
        if (server != null) {
            extraLanguages.clear();
            server.clearLabels();
            server.setLanguages(codesAndNames);
            server.setActiveLanguages(extraLanguages);
        }
    }

    /**
     * @return the languages chosen by guests right now besides the two main ones (at most MAX_EXTRA_LANGUAGES,
     * the first chosen keep their place)
     */
    public static synchronized java.util.ArrayList<String> getExtraLanguages(String firstCode, String secondCode) {
        java.util.ArrayList<String> chosen = new java.util.ArrayList<>();
        if (server != null) {
            chosen.addAll(server.getGuestLanguages());
        }
        RelayClient currentRelay = relay;
        if (currentRelay != null) {
            chosen.addAll(currentRelay.getGuestLanguages());
        }
        chosenLanguages.clear();
        chosenLanguages.addAll(chosen);
        extraLanguages.retainAll(chosen);
        for (String language : chosen) {
            if (!language.equals(firstCode) && !language.equals(secondCode) && !extraLanguages.contains(language) && extraLanguages.size() < MAX_EXTRA_LANGUAGES) {
                extraLanguages.add(language);
            }
        }
        if (server != null) {
            server.setActiveLanguages(extraLanguages);
        }
        return new java.util.ArrayList<>(extraLanguages);
    }

    /** Adds or updates a message in one more language of the room. */
    public static synchronized void publishExtra(String key, String language, String text) {
        if (server != null) {
            server.publishExtra(key, language, text);
        }
    }

    public static synchronized void setLabel(String language, int index, String text) {
        if (server != null) {
            server.setLabel(language, index, text);
        }
    }

    private static String readPage(Context context) throws Exception {
        try (InputStream input = context.getResources().openRawResource(R.raw.guest_page)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * @return the address of this phone on its Wi-Fi or on its own hotspot (the hotspot wins), or null
     */
    @Nullable
    public static String localAddress() {
        String wifi = null;
        String other = null;
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                String name = network.getName() == null ? "" : network.getName().toLowerCase();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("tun") || name.startsWith("dummy")) {
                    continue;   // mobile data and vpn: a guest can not reach those
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || !address.isSiteLocalAddress()) {
                        continue;
                    }
                    String text = address.getHostAddress();
                    if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") || name.startsWith("wlan1") || name.startsWith("wlan2")) {
                        return text;   // our own hotspot
                    }
                    if (name.startsWith("wlan") || name.startsWith("wifi")) {
                        wifi = text;
                    } else {
                        other = text;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return wifi != null ? wifi : other;
    }
}
