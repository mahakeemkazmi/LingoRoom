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

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;

import nie.translator.rtranslator.R;

/**
 * Shows the code a guest scans to follow the conversation in the browser of their own phone.
 */
public final class GuestInviteDialog {
    private static String firstLanguageName = "";

    private GuestInviteDialog() {
    }

    /**
     * @param onChanged called when the guest link has been opened or ended
     */
    public static void show(Activity activity, String hostLanguageName, String guestLanguageName, Runnable onChanged) {
        firstLanguageName = hostLanguageName;
        show(activity, guestLanguageName, GuestSession.hasRelay(activity), onChanged);
    }

    /**
     * @param online true for the internet link (the guest can talk), false for the Wi-Fi link (works with no internet)
     */
    private static void show(final Activity activity, final String guestLanguageName, final boolean online, final Runnable onChanged) {
        String address;
        try {
            address = online ? GuestSession.startOnline(activity.getApplicationContext()) : GuestSession.start(activity.getApplicationContext());
        } catch (Throwable error) {
            address = null;
        }
        onChanged.run();
        if (address == null) {
            // no Wi-Fi around: this phone can make a small private one for the guest
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.guest_title)
                    .setMessage(R.string.guest_no_network)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.guest_make_hotspot, (dialog, which) -> startHotspot(activity, guestLanguageName, onChanged))
                    .show();
            return;
        }
        String steps;
        if (online) {
            steps = activity.getString(R.string.guest_steps_online, guestLanguageName, firstLanguageName);
        } else if (GuestHotspot.isActive()) {
            steps = activity.getString(R.string.guest_steps_hotspot_page);
        } else {
            steps = activity.getString(R.string.guest_steps, guestLanguageName);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.guest_title)
                .setView(content(activity, steps, address, address, online))
                .setPositiveButton(R.string.guest_done, null)
                .setNegativeButton(R.string.guest_end, (dialog, which) -> {
                    GuestSession.stop();
                    onChanged.run();
                });
        if (GuestSession.hasRelay(activity)) {
            builder.setNeutralButton(online ? R.string.guest_use_wifi : R.string.guest_use_internet, (dialog, which) -> show(activity, guestLanguageName, !online, onChanged));
        }
        builder.show();
    }

    private static void startHotspot(final Activity activity, final String guestLanguageName, final Runnable onChanged) {
        if (!GuestHotspot.hasPermission(activity)) {
            GuestHotspot.requestPermission(activity);
            Toast.makeText(activity, R.string.guest_hotspot_permission, Toast.LENGTH_LONG).show();
            return;
        }
        GuestHotspot.start(activity, (name, password) -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (name == null || password == null) {
                GuestHotspot.stop();
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.guest_title)
                        .setMessage(R.string.guest_hotspot_failed)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
            // first code: the guest joins the private Wi-Fi. Second code (next dialog): the guest opens the page
            String steps = activity.getString(R.string.guest_steps_hotspot_join, name, password);
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.guest_title)
                    .setView(content(activity, steps, GuestHotspot.joinCode(), null, false))
                    .setCancelable(false)
                    .setNegativeButton(R.string.guest_end, (dialog, which) -> {
                        GuestSession.stop();
                        GuestHotspot.stop();
                        onChanged.run();
                    })
                    .setPositiveButton(R.string.guest_next, (dialog, which) -> openWhenReady(activity, guestLanguageName, onChanged, 0))
                    .show();
        });
    }

    /** The address of the phone on its new hotspot can take a moment to appear. */
    private static void openWhenReady(final Activity activity, final String guestLanguageName, final Runnable onChanged, final int attempt) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (GuestSession.localAddress() != null || attempt >= 8) {
            show(activity, guestLanguageName, false, onChanged);
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(() -> openWhenReady(activity, guestLanguageName, onChanged, attempt + 1), 500);
        }
    }

    private static ScrollView content(final Activity activity, String steps, String code, final String link, boolean canSend) {
        int padding = dp(activity, 20);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(padding, dp(activity, 8), padding, 0);

        TextView stepsView = new TextView(activity);
        stepsView.setText(steps);
        stepsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        content.addView(stepsView);

        Bitmap bitmap = qrCode(code, dp(activity, 240));
        if (bitmap != null) {
            ImageView image = new ImageView(activity);
            image.setImageBitmap(bitmap);
            image.setContentDescription(activity.getString(R.string.guest_title));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(activity, 240), dp(activity, 240));
            params.topMargin = dp(activity, 12);
            content.addView(image, params);
        }
        if (link != null) {
            TextView linkView = new TextView(activity);
            linkView.setText(link);
            linkView.setTextIsSelectable(true);
            linkView.setGravity(Gravity.CENTER);
            linkView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            linkView.setPadding(0, dp(activity, 8), 0, 0);
            content.addView(linkView);
        }
        if (link != null && canSend) {
            // decides whether this phone listens for both languages and whether the guests need to hear the originals
            CheckBox together = new CheckBox(activity);
            together.setText(R.string.guest_same_room);
            together.setChecked(GuestSession.isSameRoom());
            together.setOnCheckedChangeListener((button, checked) -> GuestSession.setSameRoom(checked));
            LinearLayout.LayoutParams togetherParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            togetherParams.topMargin = dp(activity, 8);
            content.addView(together, togetherParams);
        }
        if (link != null && canSend) {
            // the other person can be anywhere: the link can be sent with any messaging app
            Button sendButton = new Button(activity);
            sendButton.setText(R.string.guest_send_link);
            sendButton.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, activity.getString(R.string.guest_send_text, link));
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.guest_send_link)));
                } catch (Throwable ignored) {
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(activity, 8);
            content.addView(sendButton, params);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }

    private static Bitmap qrCode(String text, int size) {
        try {
            EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (Throwable error) {
            return null;
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
