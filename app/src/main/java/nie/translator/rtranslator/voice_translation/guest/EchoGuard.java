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

import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * When the phone of a guest reads a phrase aloud, the microphone of the host phone can hear it.
 * This remembers the phrases sent to the guest for a short time, to recognize them when they come back
 * through the microphone, so that they are not translated a second time. Pure Java.
 */
public final class EchoGuard {
    private static final long MEMORY_MS = 25000;
    private static final int MAX_PHRASES = 8;

    private static final class Phrase {
        HashSet<String> words;
        String joined;
        long time;
    }

    private final ArrayDeque<Phrase> phrases = new ArrayDeque<>();

    private static String normalize(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (Character.isLetterOrDigit(c)) {
                out.appendCodePoint(Character.toLowerCase(c));
            } else if (Character.getType(c) == Character.NON_SPACING_MARK || Character.getType(c) == Character.COMBINING_SPACING_MARK) {
                out.appendCodePoint(c);   // vowel signs of Indian scripts belong to the word
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
        }
        return out.toString().trim();
    }

    private static HashSet<String> words(String normalized) {
        HashSet<String> result = new HashSet<>();
        for (String word : normalized.split(" ")) {
            if (!word.isEmpty()) result.add(word);
        }
        return result;
    }

    /** Remembers a phrase that the phone of the guest is going to read aloud. */
    public synchronized void remember(String text, long now) {
        if (text == null) return;
        String normalized = normalize(text);
        if (normalized.isEmpty()) return;
        Phrase phrase = new Phrase();
        phrase.joined = normalized.replace(" ", "");
        phrase.words = words(normalized);
        phrase.time = now;
        phrases.addLast(phrase);
        while (phrases.size() > MAX_PHRASES) phrases.removeFirst();
    }

    /** @return true if the text heard by the microphone is (part of) a phrase remembered a short time ago */
    public synchronized boolean isEcho(String heard, long now) {
        if (heard == null) return false;
        String normalized = normalize(heard);
        if (normalized.isEmpty()) return false;
        String joined = normalized.replace(" ", "");
        HashSet<String> heardWords = words(normalized);
        for (Phrase phrase : phrases) {
            if (now - phrase.time > MEMORY_MS) continue;
            if (joined.length() >= 6 && (phrase.joined.contains(joined) || joined.contains(phrase.joined))) {
                return true;
            }
            int common = 0;
            for (String word : heardWords) {
                if (phrase.words.contains(word)) common++;
            }
            // most of what was heard is in the phrase: the recognition of an echo is never perfect
            if (heardWords.size() >= 2 && common * 10 >= heardWords.size() * 7) {
                return true;
            }
            if (heardWords.size() == 1 && phrase.words.size() <= 2 && common == 1) {
                return true;
            }
        }
        return false;
    }
}
