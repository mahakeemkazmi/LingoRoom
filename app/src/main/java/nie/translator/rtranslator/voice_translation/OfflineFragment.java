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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.Locale;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.settings.SettingsActivity;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.LanguageFlags;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.indic.IndicSpeechEngine;

/**
 * What Lingo Room keeps on the phone to work with no internet, and the optional language packs.
 */
public class OfflineFragment extends Fragment {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private VoiceTranslationActivity activity;
    private TextView storageText;
    private LinearLayout packs;
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshPacks();
            mainHandler.postDelayed(this, 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_offline, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        activity = (VoiceTranslationActivity) requireActivity();
        storageText = view.findViewById(R.id.offlineStorage);
        packs = view.findViewById(R.id.offlinePacks);
        for (int i = 0; i < IndicSpeechEngine.LANGUAGES.length; i++) {
            LayoutInflater.from(activity).inflate(R.layout.component_offline_pack, packs, true);
        }
        view.findViewById(R.id.offlineSettings).setOnClickListener(v -> {
            Intent intent = new Intent(activity, SettingsActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        BottomNavBar.setup(view, activity, BottomNavBar.OFFLINE);
    }

    @Override
    public void onResume() {
        super.onResume();
        mainHandler.post(refresher);
        countStorage();
    }

    @Override
    public void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(refresher);
    }

    private static long sizeOf(@Nullable File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    private void countStorage() {
        final Context context = activity.getApplicationContext();
        new Thread("offlineStorage") {
            @Override
            public void run() {
                long bytes = sizeOf(context.getFilesDir());
                try {
                    bytes += sizeOf(context.getExternalFilesDir(null));
                } catch (Throwable ignored) {
                }
                final String size = Formatter.formatShortFileSize(context, bytes);
                mainHandler.post(() -> {
                    if (isAdded()) {
                        storageText.setText(getString(R.string.offline_storage, size));
                    }
                });
            }
        }.start();
    }

    private void refreshPacks() {
        if (!isAdded()) {
            return;
        }
        final Context context = activity.getApplicationContext();
        String downloading = IndicSpeechEngine.getDownloadingLanguage();
        for (int i = 0; i < IndicSpeechEngine.LANGUAGES.length && i < packs.getChildCount(); i++) {
            final String language = IndicSpeechEngine.LANGUAGES[i];
            View row = packs.getChildAt(i);
            String name = new Locale(language).getDisplayLanguage();
            String flag = LanguageFlags.flag(new CustomLocale(language));
            ((TextView) row.findViewById(R.id.packName)).setText(flag.isEmpty() ? name : flag + "  " + name);
            TextView status = row.findViewById(R.id.packStatus);
            Button action = row.findViewById(R.id.packAction);
            Button delete = row.findViewById(R.id.packDelete);
            if (language.equals(downloading)) {
                status.setText(getString(R.string.offline_pack_downloading, IndicSpeechEngine.getDownloadPercent()));
                action.setVisibility(View.GONE);
                delete.setVisibility(View.GONE);
            } else if (IndicSpeechEngine.isDownloaded(context, language)) {
                final boolean enabled = IndicSpeechEngine.isEnabled(context, language);
                String size = Formatter.formatShortFileSize(context, IndicSpeechEngine.downloadedBytes(context, language));
                status.setText(getString(enabled ? R.string.offline_pack_on : R.string.offline_pack_off, size));
                action.setVisibility(View.VISIBLE);
                action.setText(enabled ? R.string.offline_turn_off : R.string.offline_turn_on);
                action.setOnClickListener(v -> {
                    IndicSpeechEngine.setEnabled(context, language, !enabled);
                    refreshPacks();
                });
                delete.setVisibility(View.VISIBLE);
                delete.setOnClickListener(v -> new AlertDialog.Builder(activity)
                        .setMessage(getString(R.string.offline_delete_question, name))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.history_delete, (dialog, which) -> {
                            IndicSpeechEngine.delete(context, language);
                            refreshPacks();
                            countStorage();
                        })
                        .show());
            } else {
                status.setText(R.string.offline_pack_missing);
                delete.setVisibility(View.GONE);
                action.setVisibility(View.VISIBLE);
                action.setText(R.string.offline_download);
                action.setEnabled(downloading == null);
                action.setOnClickListener(v -> {
                    boolean started = IndicSpeechEngine.download(context, language, new IndicSpeechEngine.DownloadListener() {
                        @Override
                        public void onProgress(int percent) {
                        }

                        @Override
                        public void onFinished(final boolean success) {
                            if (success) {
                                IndicSpeechEngine.setEnabled(context, language, true);
                            }
                            mainHandler.post(() -> {
                                if (!success) {
                                    Toast.makeText(context, R.string.offline_download_failed, Toast.LENGTH_LONG).show();
                                }
                                if (isAdded()) {
                                    countStorage();
                                }
                            });
                        }
                    });
                    if (!started) {
                        Toast.makeText(context, R.string.offline_one_at_a_time, Toast.LENGTH_SHORT).show();
                    }
                    refreshPacks();
                });
            }
            if (action.getVisibility() == View.VISIBLE && IndicSpeechEngine.isDownloaded(context, language)) {
                action.setEnabled(true);
            }
        }
    }
}
