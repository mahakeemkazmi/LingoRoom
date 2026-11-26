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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private GuestInviteDialog() {
    }

    /**
     * @param onChanged called when the guest link has been opened or ended
     */
    public static void show(Activity activity, String guestLanguageName, Runnable onChanged) {
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
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.guest_title)
                    .setMessage(R.string.guest_no_network)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        int padding = dp(activity, 20);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(padding, dp(activity, 8), padding, 0);

        TextView steps = new TextView(activity);
        steps.setText(activity.getString(online ? R.string.guest_steps_online : R.string.guest_steps, guestLanguageName));
        steps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        content.addView(steps);

        Bitmap code = qrCode(address, dp(activity, 240));
        if (code != null) {
            ImageView image = new ImageView(activity);
            image.setImageBitmap(code);
            image.setContentDescription(activity.getString(R.string.guest_title));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(activity, 240), dp(activity, 240));
            params.topMargin = dp(activity, 12);
            content.addView(image, params);
        }

        TextView link = new TextView(activity);
        link.setText(address);
        link.setTextIsSelectable(true);
        link.setGravity(Gravity.CENTER);
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        link.setPadding(0, dp(activity, 8), 0, 0);
        content.addView(link);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.guest_title)
                .setView(content)
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
