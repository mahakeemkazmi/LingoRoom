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

import java.util.ArrayList;
import java.util.List;

/**
 * Names and places are what speech recognition and translation spell worst. Given the words a user cares about
 * (their "My words" list), this finds words in a text that are close to one of them and puts the right spelling.
 * A word is only matched against words written in the same script. Pure Java.
 */
public final class WordMatcher {
    private static final int MIN_LENGTH = 5;   // short words look like too many other words

    private static final class Known {
        String word;
        String key;
        int script;
        int parts;
    }

    private final ArrayList<Known> known = new ArrayList<>();

    public WordMatcher(List<String> words) {
        for (String word : words) {
            if (word == null) continue;
            String trimmed = word.trim();
            String key = key(trimmed);
            if (key.length() < MIN_LENGTH) continue;
            Known item = new Known();
            item.word = trimmed;
            item.key = key;
            item.script = script(trimmed);
            item.parts = trimmed.split("\\s+").length;
            known.add(item);
        }
    }

    public boolean isEmpty() {
        return known.isEmpty();
    }

    /** @return 1 Latin, 2 Arabic script (Urdu), 3 Devanagari, 4 Telugu, 5 Tamil, 0 anything else */
    static int script(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return 1;
            if (c >= 0x0600 && c <= 0x06FF) return 2;
            if (c >= 0x0900 && c <= 0x097F) return 3;
            if (c >= 0x0C00 && c <= 0x0C7F) return 4;
            if (c >= 0x0B80 && c <= 0x0BFF) return 5;
        }
        return 0;
    }

    /** The letters of a word with everything that is often heard or written differently made the same. */
    static String key(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            // Urdu: marks removed, letters that sound alike made the same
            if (c >= 0x064B && c <= 0x0652 || c == 0x0670 || c == 0x0640 || c == 0x0621 || c == 0x0626 || c == 0x0654) continue;
            switch (c) {
                case 0x062D: case 0x06BE: case 0x0629: case 0x06C1: case 0x06C3: case 0x0647: c = 0x06C1; break;   // heh family
                case 0x0688: c = 0x062F; break;   // ddal
                case 0x0679: case 0x0637: c = 0x062A; break;   // tteh, tah
                case 0x0691: c = 0x0631; break;   // rreh
                case 0x0635: case 0x062B: c = 0x0633; break;   // sad, theh
                case 0x0630: case 0x0636: case 0x0638: c = 0x0632; break;   // zal, zad, zah
                case 0x0642: case 0x0643: c = 0x06A9; break;   // qaf, arabic kaf
                case 0x0622: case 0x0623: case 0x0639: c = 0x0627; break;   // alef forms, ain
                case 0x06D2: case 0x064A: case 0x0649: c = 0x06CC; break;   // yeh forms
                case 0x06BA: continue;   // noon ghunna
                default:
            }
            // Devanagari: nukta and nasal marks removed, long vowels made short
            if (c == 0x093C || c == 0x0901 || c == 0x0902 || c == 0x094D) continue;
            switch (c) {
                case 0x0940: c = 0x093F; break;
                case 0x0942: c = 0x0941; break;
                case 0x0948: c = 0x0947; break;
                case 0x094C: c = 0x094B; break;
                case 0x0906: c = 0x0905; break;
                case 0x0908: c = 0x0907; break;
                case 0x090A: c = 0x0909; break;
                default:
            }
            if (!Character.isLetterOrDigit(c) && Character.getType(c) != Character.NON_SPACING_MARK && Character.getType(c) != Character.COMBINING_SPACING_MARK) {
                continue;   // spaces and punctuation: a name in two words is compared as one
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) == c) continue;   // doubled letters
            out.append(c);
        }
        return out.toString();
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static boolean isPunctuation(char c) {
        return ".,!?;:\"'()[]\u0964\u06D4\u060C\u061F\u061B".indexOf(c) >= 0;
    }

    private int lastDistance;

    /** @return the known word a piece of text stands for, or null. lastDistance then says how far it was. */
    private String match(String piece, int pieces) {
        String key = key(piece);
        if (key.length() < MIN_LENGTH - 1) return null;
        int script = script(piece);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Known item : known) {
            if (item.script != script || key.charAt(0) != item.key.charAt(0)) continue;
            int longer = Math.max(key.length(), item.key.length());
            // several words of the text taken together for a name of one word: only if they fit it almost exactly
            // English is heard and written well, so only small slips are corrected there. The other scripts need more room
            int allowed = (int) Math.floor(longer * (pieces > item.parts ? 0.15 : script == 1 ? 0.2 : 0.3));
            if (Math.abs(key.length() - item.key.length()) > allowed) continue;
            int distance = distance(key, item.key);
            if (distance <= allowed && distance < bestDistance) {
                bestDistance = distance;
                best = item.word;
            }
        }
        lastDistance = bestDistance;
        return best;
    }

    /** @return the text with the words close to a known word replaced by that word */
    public synchronized String fix(String text) {
        if (text == null || text.isEmpty() || known.isEmpty()) {
            return text;
        }
        String[] tokens = text.split(" ");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < tokens.length) {
            String replacement = null;
            int used = 1;
            // a name can be heard as two or three pieces: the run of words that fits a known word best wins
            int bestDistance = Integer.MAX_VALUE;
            for (int size = 1; size <= Math.min(3, tokens.length - i) && !tokens[i].isEmpty(); size++) {
                if (size > 1 && isPunctuation(tokens[i + size - 2].charAt(Math.max(0, tokens[i + size - 2].length() - 1)))) break;   // not across a comma or full stop
                if (tokens[i + size - 1].isEmpty()) break;
                StringBuilder piece = new StringBuilder();
                for (int k = 0; k < size; k++) {
                    if (k > 0) piece.append(' ');
                    piece.append(tokens[i + k]);
                }
                String found = match(piece.toString(), size);
                if (found != null && lastDistance < bestDistance) {
                    bestDistance = lastDistance;
                    replacement = found;
                    used = size;
                }
            }
            if (out.length() > 0) out.append(' ');
            if (replacement == null) {
                out.append(tokens[i]);
            } else {
                String first = tokens[i];
                String last = tokens[i + used - 1];
                int start = 0;
                while (start < first.length() && isPunctuation(first.charAt(start))) start++;
                int end = last.length();
                while (end > 0 && isPunctuation(last.charAt(end - 1))) end--;
                out.append(first, 0, start).append(replacement).append(last.substring(end));
            }
            i += used;
        }
        return out.toString();
    }
}
