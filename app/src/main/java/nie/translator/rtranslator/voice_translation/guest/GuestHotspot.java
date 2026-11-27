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

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

/**
 * A small private Wi-Fi made by this phone for one guest conversation, for places with no Wi-Fi and no internet.
 * Android chooses its name and password. It has no internet access and ends with the guest link.
 */
public final class GuestHotspot {
    public interface Listener {
        /** @param name null if the hotspot could not be started */
        void onResult(@Nullable String name, @Nullable String password);
    }

    private static WifiManager.LocalOnlyHotspotReservation reservation;
    private static String name;
    private static String password;

    private GuestHotspot() {
    }

    public static String requiredPermission() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestPermission(Activity activity) {
        activity.requestPermissions(new String[]{requiredPermission()}, 7341);
    }

    public static synchronized boolean isActive() {
        return reservation != null;
    }

    /** @return the text of a code that makes the camera of a phone offer to join the hotspot */
    public static synchronized String joinCode() {
        return "WIFI:T:WPA;S:" + escape(name) + ";P:" + escape(password) + ";;";
    }

    private static String escape(@Nullable String text) {
        StringBuilder out = new StringBuilder();
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == 92 || c == ';' || c == ',' || c == ':' || c == '"') {
                    out.append((char) 92);
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    @SuppressWarnings("deprecation")
    public static void start(Context context, final Listener listener) {
        final Handler handler = new Handler(Looper.getMainLooper());
        synchronized (GuestHotspot.class) {
            if (reservation != null) {
                listener.onResult(name, password);
                return;
            }
        }
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation started) {
                    String startedName = null;
                    String startedPassword = null;
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            android.net.wifi.SoftApConfiguration configuration = started.getSoftApConfiguration();
                            startedName = configuration.getSsid();
                            startedPassword = configuration.getPassphrase();
                        } else {
                            WifiConfiguration configuration = started.getWifiConfiguration();
                            if (configuration != null) {
                                startedName = configuration.SSID;
                                startedPassword = configuration.preSharedKey;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (startedName != null && startedName.length() > 1 && startedName.charAt(0) == '"' && startedName.charAt(startedName.length() - 1) == '"') {
                        startedName = startedName.substring(1, startedName.length() - 1);
                    }
                    synchronized (GuestHotspot.class) {
                        reservation = started;
                        name = startedName;
                        password = startedPassword;
                    }
                    listener.onResult(startedName, startedPassword);
                }

                @Override
                public void onFailed(int reason) {
                    listener.onResult(null, null);
                }
            }, handler);
        } catch (Throwable error) {
            // no permission, location turned off, or a hotspot is already on
            handler.post(() -> listener.onResult(null, null));
        }
    }

    public static synchronized void stop() {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (Throwable ignored) {
            }
            reservation = null;
            name = null;
            password = null;
        }
    }
}
