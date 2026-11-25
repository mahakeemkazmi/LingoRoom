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

package nie.translator.rtranslator.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.indic.IndicSpeechEngine;

/**
 * Switch to listen to one Indian language with a speech model built for Indian languages.
 * The language comes from the key of the preference. Turning it on for the first time downloads the model
 * (about 200 MB). The switch stays on during the download and goes back off only if the download fails.
 */
public class IndicSpeechPreference extends SwitchPreference {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String language;

    public IndicSpeechPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize();
    }

    public IndicSpeechPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public IndicSpeechPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public IndicSpeechPreference(Context context) {
        super(context);
        initialize();
    }

    private void initialize() {
        language = IndicSpeechEngine.languageOfPreferenceKey(getKey());
        // The switch position is stored by the settings screen itself, like the other switches of this screen.
        // The engine reads its own copy from the "default" preferences file, written by IndicSpeechEngine.setEnabled.
        setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (language == null) {
                    return false;
                }
                final Context context = getContext().getApplicationContext();
                boolean enable = (Boolean) newValue;
                if (enable && !IndicSpeechEngine.isDownloaded(context, language)) {
                    String busyWith = IndicSpeechEngine.getDownloadingLanguage();
                    if (busyWith != null && !busyWith.equals(language)) {
                        // one download at a time
                        Toast.makeText(context, R.string.preference_indic_speech_wait, Toast.LENGTH_LONG).show();
                        return false;
                    }
                    IndicSpeechEngine.setEnabled(context, language, true);
                    // the switch stays on while the model downloads, the engine only starts using it once it is complete
                    startDownload(context);
                    return true;
                }
                IndicSpeechEngine.setEnabled(context, language, enable);
                return true;
            }
        });
    }

    @Override
    public void onAttached() {
        super.onAttached();
        if (language == null) {
            return;
        }
        Context context = getContext().getApplicationContext();
        if (language.equals(IndicSpeechEngine.getDownloadingLanguage())) {
            setSummary(context.getString(R.string.preference_indic_speech_downloading, IndicSpeechEngine.getDownloadPercent()));
        } else if (isChecked() && !IndicSpeechEngine.isDownloaded(context, language)) {
            // the switch was left on but the model is missing (for example after a failed download)
            setChecked(false);
            IndicSpeechEngine.setEnabled(context, language, false);
        }
    }

    /** Stores the switch position where the settings screen reads it, even if this screen is no longer open. */
    private void storeSwitchPosition(Context context, boolean checked) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(IndicSpeechEngine.preferenceKey(language), checked).apply();
    }

    private void startDownload(final Context context) {
        setSummary(context.getString(R.string.preference_indic_speech_downloading, 0));
        IndicSpeechEngine.download(context, language, new IndicSpeechEngine.DownloadListener() {
            @Override
            public void onProgress(final int percent) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setSummary(context.getString(R.string.preference_indic_speech_downloading, percent));
                    }
                });
            }

            @Override
            public void onFinished(final boolean success) {
                IndicSpeechEngine.setEnabled(context, language, success);
                storeSwitchPosition(context, success);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setSummary(R.string.preference_description_indic_speech);
                        setChecked(success);
                        Toast.makeText(context, success ? R.string.preference_indic_speech_ready : R.string.preference_indic_speech_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }
}
