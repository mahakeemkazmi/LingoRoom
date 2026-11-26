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
            "To speak, tap the microphone on your keyboard"
    };

    public interface Host {
        void onGuestText(String text);

        void onGuestSessionStarted();
    }

    private static GuestServer server;
    private static Host host;

    private GuestSession() {
    }

    /** Called by the Conversation service while it is alive. */
    public static synchronized void setHost(@Nullable Host newHost) {
        host = newHost;
    }

    /** @return the address of the guest page, or null if the phone is not on a Wi-Fi or hotspot */
    @Nullable
    public static synchronized String start(Context context) throws Exception {
        String address = localAddress();
        if (address == null) {
            return null;
        }
        if (server == null || !server.isRunning()) {
            GuestServer created = new GuestServer(readPage(context), LABELS);
            created.setTextListener(text -> {
                Host current;
                synchronized (GuestSession.class) {
                    current = host;
                }
                if (current != null) {
                    current.onGuestText(text);
                }
            });
            created.start();
            server = created;
            if (host != null) {
                host.onGuestSessionStarted();
            }
        }
        return "http://" + address + ":" + server.getPort() + server.getPath();
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public static synchronized boolean isActive() {
        return server != null && server.isRunning();
    }

    public static synchronized boolean isGuestConnected() {
        return server != null && server.isGuestConnected();
    }

    public static synchronized void publish(String key, boolean hostSpoke, String original, String translated, boolean isFinal) {
        if (server != null) {
            server.publish(key, hostSpoke, original, translated, isFinal);
        }
    }

    public static synchronized void setSpeechLanguage(String languageCode) {
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
    static String localAddress() {
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
