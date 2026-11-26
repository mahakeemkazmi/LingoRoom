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

package nie.translator.rtranslator.tools;

import java.util.HashMap;

/**
 * A flag for a language, shown next to its name. A language is not a country, so this is only a visual hint:
 * each language gets the flag of one country where it is widely spoken.
 */
public final class LanguageFlags {
    private static final HashMap<String, String> REGIONS = new HashMap<>();

    static {
        String[] pairs = {
                "en", "GB", "ur", "PK", "hi", "IN", "te", "IN", "ta", "IN", "bn", "BD", "mr", "IN", "gu", "IN", "kn", "IN",
                "ml", "IN", "pa", "IN", "or", "IN", "as", "IN", "ne", "NP", "si", "LK", "ar", "SA", "fa", "IR", "ps", "AF",
                "he", "IL", "tr", "TR", "id", "ID", "ms", "MY", "jv", "ID", "tl", "PH", "fil", "PH", "th", "TH", "vi", "VN",
                "km", "KH", "lo", "LA", "my", "MM", "zh", "CN", "yue", "HK", "ja", "JP", "ko", "KR", "mn", "MN", "fr", "FR",
                "de", "DE", "es", "ES", "pt", "PT", "it", "IT", "nl", "NL", "ru", "RU", "uk", "UA", "be", "BY", "pl", "PL",
                "cs", "CZ", "sk", "SK", "hu", "HU", "ro", "RO", "bg", "BG", "el", "GR", "hr", "HR", "sr", "RS", "bs", "BA",
                "sl", "SI", "mk", "MK", "sq", "AL", "lt", "LT", "lv", "LV", "et", "EE", "fi", "FI", "sv", "SE", "da", "DK",
                "no", "NO", "nb", "NO", "nn", "NO", "is", "IS", "ga", "IE", "cy", "GB", "mt", "MT", "ca", "ES", "gl", "ES",
                "eu", "ES", "af", "ZA", "zu", "ZA", "xh", "ZA", "sw", "KE", "am", "ET", "so", "SO", "ha", "NG", "yo", "NG",
                "ig", "NG", "az", "AZ", "kk", "KZ", "uz", "UZ", "ky", "KG", "tg", "TJ", "tk", "TM", "ka", "GE", "hy", "AM",
                "lb", "LU", "ht", "HT", "mg", "MG", "mi", "NZ", "sd", "PK", "bo", "CN", "ug", "CN"
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            REGIONS.put(pairs[i], pairs[i + 1]);
        }
    }

    private LanguageFlags() {
    }

    /** @return the flag for the language, or an empty text if we have none */
    public static String flag(CustomLocale language) {
        if (language == null || language.getCode() == null) {
            return "";
        }
        String code = language.getCode().toLowerCase();
        int separator = Math.max(code.indexOf('-'), code.indexOf('_'));
        if (separator > 0) {
            code = code.substring(0, separator);
        }
        String region = REGIONS.get(code);
        if (region == null) {
            return "";
        }
        // a flag is written with the two letters of the country as "regional indicator" symbols
        return new String(Character.toChars(0x1F1E6 + region.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + region.charAt(1) - 'A'));
    }

    /** @return the name of the language with its flag in front */
    public static String label(CustomLocale language) {
        if (language == null) {
            return "";
        }
        String flag = flag(language);
        String name = language.getDisplayNameWithoutTTS();
        return flag.isEmpty() ? name : flag + "  " + name;
    }
}
