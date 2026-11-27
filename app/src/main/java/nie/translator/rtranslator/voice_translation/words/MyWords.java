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

package nie.translator.rtranslator.voice_translation.words;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The words a user wants the app to always get right (names, places), kept on the phone, and the setting for
 * Deccani words. A word typed in English also gets a spelling in each language it is used with: the app asks
 * the translator for it once and remembers it.
 */
public final class MyWords {
    public static final String PREFERENCE_WORDS = "myWords";
    public static final String PREFERENCE_DECCANI = "understandDeccani";
    private static final String PREFERENCE_FORMS = "myWordsForms";
    private static final int MAX_WORDS = 200;

    private final SharedPreferences preferences;
    private final ArrayList<String> words = new ArrayList<>();
    private final HashMap<String, String> forms = new HashMap<>();   // "language|word" to the spelling in that language
    private final HashMap<String, WordMatcher> matchers = new HashMap<>();
    private final boolean deccani;

    public MyWords(Context context) {
        preferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        deccani = preferences.getBoolean(PREFERENCE_DECCANI, true);
        for (String line : preferences.getString(PREFERENCE_WORDS, "").split("\n")) {
            String word = line.replaceAll("\\s+", " ").trim();
            if (word.length() >= 3 && word.length() <= 40 && !words.contains(word) && words.size() < MAX_WORDS) {
                words.add(word);
            }
        }
        for (String line : preferences.getString(PREFERENCE_FORMS, "").split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                forms.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
    }

    public boolean isEmpty() {
        return words.isEmpty();
    }

    /** @return the text as it should be given to the translator: Deccani words made standard, known words spelled right */
    public String fixHeard(String text, String language) {
        if (text == null || language == null) {
            return text;
        }
        if (deccani && (language.startsWith("ur") || language.startsWith("hi"))) {
            text = DeccaniNormalizer.normalize(text);
        }
        return fix(text, language);
    }

    /** @return the text with the words close to one of the user's words replaced by it */
    public String fix(String text, String language) {
        if (text == null || language == null || words.isEmpty()) {
            return text;
        }
        WordMatcher matcher;
        synchronized (matchers) {
            matcher = matchers.get(language);
            if (matcher == null) {
                ArrayList<String> all = new ArrayList<>(words);
                for (String word : words) {
                    String form = forms.get(language + "|" + word);
                    if (form != null) {
                        all.add(form);
                    }
                }
                matcher = new WordMatcher(all);
                matchers.put(language, matcher);
            }
        }
        return matcher.fix(text);
    }

    /** @return the words written in English letters that have no spelling yet in a language written differently */
    public ArrayList<String> wordsWithoutForm(String language) {
        ArrayList<String> result = new ArrayList<>();
        if (language == null || language.startsWith("en")) {
            return result;
        }
        synchronized (matchers) {
            for (String word : words) {
                if (WordMatcher.script(word) == 1 && !forms.containsKey(language + "|" + word)) {
                    result.add(word);
                }
            }
        }
        return result;
    }

    /**
     * Remembers how the translator writes one of the words in a language.
     * @param form the answer of the translator. It is kept only if it looks like a name written in another script
     */
    public void setForm(String language, String word, String form) {
        String cleaned = form == null ? "" : form.replaceAll("[.\u0964\u06D4!?\"]", "").replaceAll("\\s+", " ").trim();
        boolean usable = cleaned.length() >= 3 && cleaned.length() <= 40 && WordMatcher.script(cleaned) > 1 && cleaned.split(" ").length <= word.split(" ").length + 1;
        synchronized (matchers) {
            forms.put(language + "|" + word, usable ? cleaned : "");
            matchers.remove(language);
            StringBuilder all = new StringBuilder();
            for (java.util.Map.Entry<String, String> item : forms.entrySet()) {
                if (all.length() > 0) all.append('\n');
                all.append(item.getKey()).append('=').append(item.getValue());
            }
            preferences.edit().putString(PREFERENCE_FORMS, all.toString()).apply();
        }
    }
}
