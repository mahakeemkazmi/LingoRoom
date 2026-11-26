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

package nie.translator.rtranslator.voice_translation;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.settings.SettingsActivity;

/**
 * Wires the bottom navigation bar (layout component_bottom_nav) of a main screen.
 */
public final class BottomNavBar {
    public static final int HOME = 0;
    public static final int HISTORY = 1;
    public static final int FAVORITES = 2;
    public static final int OFFLINE = 3;
    public static final int PROFILE = 4;
    private static final int[] ITEM_IDS = {R.id.navHome, R.id.navHistory, R.id.navFavorites, R.id.navOffline, R.id.navProfile};

    private BottomNavBar() {
    }

    /**
     * @param root a view that contains the bottom navigation bar
     * @param selected the item of the current screen (one of the constants of this class)
     */
    public static void setup(View root, final VoiceTranslationActivity activity, final int selected) {
        int selectedColor = themeColor(activity, R.attr.colorPrimary);
        int normalColor = themeColor(activity, R.attr.colorTextLevel3);
        for (int i = 0; i < ITEM_IDS.length; i++) {
            View view = root.findViewById(ITEM_IDS[i]);
            if (!(view instanceof ViewGroup)) {
                continue;
            }
            ViewGroup item = (ViewGroup) view;
            int color = i == selected ? selectedColor : normalColor;
            if (item.getChildCount() >= 2) {
                if (item.getChildAt(0) instanceof ImageView) {
                    ImageViewCompat.setImageTintList((ImageView) item.getChildAt(0), ColorStateList.valueOf(color));
                }
                if (item.getChildAt(1) instanceof TextView) {
                    ((TextView) item.getChildAt(1)).setTextColor(color);
                }
            }
            final int target = i;
            item.setOnClickListener(v -> {
                if (target != selected) {
                    open(activity, target);
                }
            });
        }
    }

    private static void open(VoiceTranslationActivity activity, int target) {
        switch (target) {
            case HOME:
                activity.setFragment(VoiceTranslationActivity.HOME_FRAGMENT);
                break;
            case HISTORY:
                activity.setFragment(VoiceTranslationActivity.HISTORY_FRAGMENT);
                break;
            case FAVORITES:
                activity.setFragment(VoiceTranslationActivity.FAVORITES_FRAGMENT);
                break;
            default: {
                // Offline (language downloads) and Profile both live in the settings for now
                Intent intent = new Intent(activity, SettingsActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            }
        }
    }

    private static int themeColor(Context context, int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }
}
