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

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.LanguageFlags;

/**
 * The home screen: the brand, the two languages of the conversation, and the button that starts it.
 * The languages are the same ones the Conversation screen uses, so a choice made here is kept there.
 */
public class HomeFragment extends Fragment {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private VoiceTranslationActivity activity;
    private Global global;
    private TextView firstLanguageText;
    private TextView secondLanguageText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (VoiceTranslationActivity) requireActivity();
        global = (Global) activity.getApplication();
        firstLanguageText = view.findViewById(R.id.homeFirstLanguageText);
        secondLanguageText = view.findViewById(R.id.homeSecondLanguageText);

        view.findViewById(R.id.homeFirstLanguage).setOnClickListener(v -> chooseLanguage(true));
        view.findViewById(R.id.homeSecondLanguage).setOnClickListener(v -> chooseLanguage(false));
        view.findViewById(R.id.homeSwapButton).setOnClickListener(v -> swapLanguages());
        view.findViewById(R.id.homeStartButton).setOnClickListener(v -> activity.setFragment(VoiceTranslationActivity.WALKIE_TALKIE_FRAGMENT, true));
        view.findViewById(R.id.homeTypeButton).setOnClickListener(v -> activity.setFragment(VoiceTranslationActivity.TRANSLATION_FRAGMENT));
        view.findViewById(R.id.homeSettingsButton).setOnClickListener(v -> openSettings());

        BottomNavBar.setup(view, activity, BottomNavBar.HOME);
    }

    @Override
    public void onResume() {
        super.onResume();
        showLanguages();
    }

    /** Reads the two languages in the background (the first read can be slow) and shows their names. */
    private void showLanguages() {
        new Thread("homeLanguages") {
            @Override
            public void run() {
                try {
                    final CustomLocale first = global.getFirstLanguage(true);
                    final CustomLocale second = global.getSecondLanguage(true);
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        if (first != null) firstLanguageText.setText(LanguageFlags.label(first));
                        if (second != null) secondLanguageText.setText(LanguageFlags.label(second));
                    });
                } catch (Throwable ignored) {
                    // the names stay as they are, the Conversation screen has its own selectors
                }
            }
        }.start();
    }

    private void chooseLanguage(final boolean first) {
        new Thread("homeLanguageList") {
            @Override
            public void run() {
                try {
                    final ArrayList<CustomLocale> languages = new ArrayList<>(global.getLanguages(Global.RTranslatorMode.WALKIE_TALKIE_MODE, true));
                    Collections.sort(languages, new Comparator<CustomLocale>() {
                        @Override
                        public int compare(CustomLocale a, CustomLocale b) {
                            return a.getDisplayNameWithoutTTS().compareToIgnoreCase(b.getDisplayNameWithoutTTS());
                        }
                    });
                    final String[] names = new String[languages.size()];
                    for (int i = 0; i < names.length; i++) {
                        names[i] = LanguageFlags.label(languages.get(i));
                    }
                    mainHandler.post(() -> {
                        if (!isAdded() || names.length == 0) return;
                        new AlertDialog.Builder(activity)
                                .setTitle(R.string.home_choose_language)
                                .setItems(names, (dialog, which) -> {
                                    CustomLocale chosen = languages.get(which);
                                    if (first) {
                                        global.setFirstLanguage(chosen, null);
                                        firstLanguageText.setText(LanguageFlags.label(chosen));
                                    } else {
                                        global.setSecondLanguage(chosen, null);
                                        secondLanguageText.setText(LanguageFlags.label(chosen));
                                    }
                                })
                                .show();
                    });
                } catch (Throwable ignored) {
                    // no list available: the Conversation screen has its own selectors
                }
            }
        }.start();
    }

    private void swapLanguages() {
        new Thread("homeSwapLanguages") {
            @Override
            public void run() {
                try {
                    final CustomLocale first = global.getFirstLanguage(true);
                    final CustomLocale second = global.getSecondLanguage(true);
                    if (first == null || second == null) return;
                    mainHandler.post(() -> {
                        if (!isAdded()) return;
                        global.setFirstLanguage(second, null);
                        global.setSecondLanguage(first, null);
                        firstLanguageText.setText(LanguageFlags.label(second));
                        secondLanguageText.setText(LanguageFlags.label(first));
                    });
                } catch (Throwable ignored) {
                }
            }
        }.start();
    }

    private void openSettings() {
        Intent intent = new Intent(activity, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

}
