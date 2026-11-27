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
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.AttributeSet;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import nie.translator.rtranslator.R;
import nie.translator.rtranslator.voice_translation.words.MyWords;

/**
 * Opens the list of the words the app must always get right (names, places), one for each line.
 */
public class MyWordsPreference extends Preference {
    public MyWordsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public MyWordsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public MyWordsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MyWordsPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        final Context context = getContext();
        final SharedPreferences preferences = context.getApplicationContext().getSharedPreferences("default", Context.MODE_PRIVATE);
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setMinLines(5);
        input.setMaxLines(9);   // the list scrolls inside the box, so the buttons stay on the screen
        input.setVerticalScrollBarEnabled(true);
        input.setGravity(android.view.Gravity.TOP);
        input.setHint(R.string.my_words_hint);
        input.setText(preferences.getString(MyWords.PREFERENCE_WORDS, ""));
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(context);
        frame.setPadding(padding, 0, padding, 0);
        frame.addView(input);
        new AlertDialog.Builder(context)
                .setTitle(R.string.my_words_title)
                .setMessage(R.string.my_words_explanation)
                .setView(frame)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> preferences.edit().putString(MyWords.PREFERENCE_WORDS, input.getText().toString().trim()).apply())
                .show();
    }
}
