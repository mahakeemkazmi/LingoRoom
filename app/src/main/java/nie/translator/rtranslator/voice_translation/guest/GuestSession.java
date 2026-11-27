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
            "Choose your language"
    };

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

    private static String relayAddress(Context context) {
        String address = context.getString(R.string.guest_relay_url).trim();
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        return address.startsWith("https://") ? address : "";
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
            relayRoom = randomHex(16);
            final GuestServer current = server;
            relay = new RelayClient("wss://" + address.substring(8) + "/r/" + relayRoom + "/ws?role=host&key=" + randomHex(16), new RelayClient.Listener() {
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
        return address + "/r/" + relayRoom;
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

    /** Sets the two languages the guests can choose from. */
    public static synchronized void setLanguages(String firstCode, String firstName, String secondCode, String secondName) {
        if (server != null) {
            server.clearLabels();
            server.setLanguages(firstCode, firstName, secondCode, secondName);
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
