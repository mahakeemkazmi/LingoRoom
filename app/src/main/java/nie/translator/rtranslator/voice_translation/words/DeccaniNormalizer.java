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

import java.util.HashMap;

/**
 * Deccani (Hyderabadi) Urdu uses words and verb forms that standard Urdu and Hindi do not have, so the translator
 * does not understand them. This replaces the most common ones with their standard form before translation.
 * Only forms that are not ordinary words of the standard language are replaced. Pure Java.
 */
public final class DeccaniNormalizer {
    private static final HashMap<String, String> WORDS = new HashMap<>();
    // verbs that Deccani joins with the "ra / ri / re" of the continuous tense: "karra" for "kar raha"
    private static final String[] HINDI_STEMS = {"कर", "बोल", "जा", "आ", "दे", "ले", "देख", "सुन", "हो", "बैठ", "चल", "पूछ", "सोच", "बता", "समझ", "रह", "मिल", "लग", "पढ़", "लिख", "खेल", "सो", "पी", "निकल", "पहुँच", "बना", "रुक", "भेज", "ला"};
    private static final String[] URDU_STEMS = {"کر", "بول", "دے", "لے", "دیکھ", "سن", "ہو", "بیٹھ", "چل", "پوچھ", "سوچ", "بتا", "سمجھ", "رہ", "مل", "لگ", "پڑھ", "لکھ", "کھیل", "سو", "پی", "نکل", "پہنچ", "بنا", "رک", "بھیج", "لا"};

    static {
        String[] pairs = {
                // Devanagari
                "नक्को", "नहीं", "नको", "नहीं", "नक्कू", "नहीं",
                "काइकू", "क्यों", "कैकू", "क्यों", "कायकू", "क्यों", "काइको", "क्यों", "कायको", "क्यों", "कैको", "क्यों",
                "मेरेकु", "मुझे", "मेरेकू", "मुझे", "मेरेको", "मुझे", "तेरेकु", "तुझे", "तेरेकू", "तुझे", "तेरेको", "तुझे",
                "उनकु", "उनको", "उनकू", "उनको", "इनकु", "इनको", "इनकू", "इनको", "उसकु", "उसको", "उसकू", "उसको", "इसकु", "इसको", "इसकू", "इसको",
                "अपनकु", "हमें", "अपनकू", "हमें", "अपनको", "हमें",
                "हौ", "हाँ", "इच", "ही", "हल्लू", "धीरे", "हल्लु", "धीरे",
                "पोट्टी", "लड़की", "पोट्टा", "लड़का", "पोट्टे", "लड़के",
                "कित्ता", "कितना", "कित्ते", "कितने", "कित्ती", "कितनी", "इत्ता", "इतना", "इत्ते", "इतने", "इत्ती", "इतनी", "उत्ता", "उतना",
                // Urdu
                "نکو", "نہیں", "نکّو", "نہیں",
                "کائیکو", "کیوں", "کائکو", "کیوں", "کایکو", "کیوں", "کیکو", "کیوں",
                "میرےکو", "مجھے", "تیرےکو", "تجھے", "اپنکو", "ہمیں",
                "ہاؤ", "ہاں", "پوٹّی", "لڑکی", "پوٹّا", "لڑکا",
                "کتّا", "کتنا", "کتّے", "کتنے", "اتّا", "اتنا", "اتّے", "اتنے"
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            WORDS.put(pairs[i], pairs[i + 1]);
        }
        String[][] hindiEndings = {{"रा", "रहा"}, {"री", "रही"}, {"रे", "रहे"}, {"रूँ", "रहा हूँ"}, {"रूं", "रहा हूँ"}, {"रौं", "रहा हूँ"}, {"रैं", "रहे हैं"}};
        for (String stem : HINDI_STEMS) {
            for (String[] ending : hindiEndings) {
                String joined = stem + ending[0];
                // "आरी" (a saw), "जारी" (ongoing), "सोरी" (sorry), "लारी", "पीरी" stay as they are: they are ordinary words
                if (joined.equals("जारी") || joined.equals("आरी") || joined.equals("आरा") || joined.equals("सोरी") || joined.equals("लारी") || joined.equals("होरी")) {
                    continue;
                }
                WORDS.put(joined, stem + " " + ending[1]);
            }
        }
        String[][] urduEndings = {{"را", "رہا"}, {"ری", "رہی"}, {"رے", "رہے"}, {"روں", "رہا ہوں"}};
        for (String stem : URDU_STEMS) {
            for (String[] ending : urduEndings) {
                String joined = stem + ending[0];
                if (joined.equals("سورے") || joined.equals("لاری") || joined.equals("پیری") || joined.equals("ہوری") || joined.equals("سوری")) {
                    continue;
                }
                WORDS.put(joined, stem + " " + ending[1]);
            }
        }
        // "ja" and "aa" in Urdu script: only the forms that are not ordinary words ("جاری" is "ongoing", "آری" is a saw)
        WORDS.put("جارا", "جا رہا");
        WORDS.put("جارے", "جا رہے");
        WORDS.put("جاروں", "جا رہا ہوں");
        WORDS.put("آرا", "آ رہا");
        WORDS.put("آرے", "آ رہے");
        WORDS.put("آروں", "آ رہا ہوں");
    }

    private DeccaniNormalizer() {
    }

    private static boolean isPunctuation(char c) {
        return ".,!?;:\"'()[]\u0964\u06D4\u060C\u061F\u061B".indexOf(c) >= 0;
    }

    /** @return the text with the Deccani words it contains replaced by their standard form */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] tokens = text.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            int start = 0;
            int end = token.length();
            while (start < end && isPunctuation(token.charAt(start))) start++;
            while (end > start && isPunctuation(token.charAt(end - 1))) end--;
            String word = token.substring(start, end);
            String replacement = WORDS.get(word);
            // "mere ko" and "tere ko" written as two words
            if (replacement == null && i + 1 < tokens.length && end == token.length()) {
                String next = tokens[i + 1];
                int nextEnd = next.length();
                while (nextEnd > 0 && isPunctuation(next.charAt(nextEnd - 1))) nextEnd--;
                String pair = word + " " + next.substring(0, nextEnd);
                String joinedReplacement = null;
                if (pair.equals("मेरे को") || pair.equals("मेरे कू") || pair.equals("मेरे कु")) joinedReplacement = "मुझे";
                else if (pair.equals("तेरे को") || pair.equals("तेरे कू") || pair.equals("तेरे कु")) joinedReplacement = "तुझे";
                else if (pair.equals("میرے کو")) joinedReplacement = "مجھے";
                else if (pair.equals("تیرے کو")) joinedReplacement = "تجھے";
                if (joinedReplacement != null) {
                    if (out.length() > 0) out.append(' ');
                    out.append(token, 0, start).append(joinedReplacement).append(next.substring(nextEnd));
                    i++;
                    continue;
                }
            }
            if (out.length() > 0) out.append(' ');
            if (replacement == null) {
                out.append(token);
            } else {
                out.append(token, 0, start).append(replacement).append(token.substring(end));
            }
        }
        return out.toString();
    }
}
