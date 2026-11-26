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

import nie.translator.rtranslator.R;

/**
 * The guest conversation that is currently open, if any. The Conversation screen starts it,
 * the Conversation service feeds it and receives the texts written by the guest.
 */
public final class GuestSession {
    /** Texts of the guest page. The service translates them into the language of the guest. */
    public static final String[] LABELS = {
            "Write your message here",
            "Send",
            "Read aloud",
            "To speak, tap the box below, then the microphone on your keyboard",
            "Hold the microphone to speak",
            "Tap here to hear the conversation"
    };

    public interface Host {
        void onGuestText(String text);

        /** @param samples what the guest said, mono, 16 kHz */
        void onGuestAudio(float[] samples);

        void onGuestSessionStarted();
    }

    private static GuestServer server;
    private static volatile RelayClient relay;
    private static volatile long relaySentRevision;
    private static String relayRoom;
    private static GuestVoice voice;
    private static int soundCounter;
    private static volatile String speechLanguage = "";
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

    private static void ensureServer(Context context) throws Exception {
        if (server != null) {
            return;
        }
        final GuestServer created = new GuestServer(readPage(context), LABELS);
        created.setTextListener(GuestSession::deliverText);
        created.setChangeListener(() -> {
            RelayClient current = relay;
            if (current != null && current.isOpen()) {
                long sent = relaySentRevision;
                relaySentRevision = created.getRevision();
                current.send(created.eventsJson(sent));
            }
        });
        server = created;
        voice = new GuestVoice(context, (key, wav) -> {
            created.addSound(key, wav);
            RelayClient current = relay;
            if (current != null && current.isOpen()) {
                // over the internet the sound travels with the conversation: first its name, then the sound itself
                current.send("{\"type\":\"audio\",\"key\":" + GuestServer.quote(key) + "}");
                current.send(wav);
            }
        });
        if (host != null) {
            host.onGuestSessionStarted();
        }
    }

    private static void deliverText(String text) {
        Host current;
        synchronized (GuestSession.class) {
            current = host;
        }
        if (current != null) {
            current.onGuestText(text);
        }
    }

    /**
     * Opens the guest conversation over the internet, through the guest relay.
     *
     * @return the address of the guest page, or null if this build has no relay
     */
    @Nullable
    public static synchronized String startOnline(Context context) throws Exception {
        String address = relayAddress(context);
        if (address.isEmpty()) {
            return null;
        }
        ensureServer(context);
        if (relay == null) {
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
                public void onGuestText(String text) {
                    deliverText(text);
                }

                @Override
                public void onGuestAudio(float[] samples) {
                    Host target;
                    synchronized (GuestSession.class) {
                        target = host;
                    }
                    if (target != null) {
                        target.onGuestAudio(samples);
                    }
                }
            });
            relay.connect();
        }
        return address + "/r/" + relayRoom;
    }

    /**
     * Opens the guest conversation on the local network, with no internet.
     *
     * @return the address of the guest page, or null if the phone is not on a Wi-Fi or hotspot
     */
    @Nullable
    public static synchronized String start(Context context) throws Exception {
        String address = localAddress();
        if (address == null) {
            return null;
        }
        ensureServer(context);
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
        GuestHotspot.stop();
    }

    public static synchronized boolean isActive() {
        return server != null;
    }

    /**
     * @return true if the phone of a guest is reading the conversation aloud: this phone then does not need
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

    public static synchronized void publish(String key, boolean hostSpoke, String original, String translated, boolean isFinal) {
        if (server != null) {
            server.publish(key, hostSpoke, original, translated, isFinal);
        }
    }

    /**
     * Makes the phone of the guest say a phrase. Phrases are heard in the order of the calls.
     */
    public static synchronized void speak(String phrase) {
        if (voice != null && !speechLanguage.isEmpty()) {
            voice.synthesize("s" + (soundCounter++), phrase, speechLanguage);
        }
    }

    public static synchronized void setSpeechLanguage(String languageCode) {
        speechLanguage = languageCode == null ? "" : languageCode;
        if (server != null) {
            server.setSpeechLanguage(languageCode);
        }
    }

    public static synchronized void setLabel(int index, String text) {
        if (server != null) {
            server.setLabel(index, text);
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
