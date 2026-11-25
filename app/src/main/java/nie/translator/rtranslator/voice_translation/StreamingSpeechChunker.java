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

/**
 * Splits a translation that is still being generated into phrases that are safe to speak right away.
 * <br /><br />
 * The translator emits a growing partial text for each result, followed by one final text. Feed every
 * one of them to {@link #nextChunk(long, String, boolean)}: it returns the next phrase to speak, or null
 * when there is nothing new that is safe to speak yet.
 * <br /><br />
 * A phrase is only released once the text has moved past its closing punctuation, so the words inside it
 * can no longer change (this relies on greedy decoding, where the partial text only ever grows).
 */
public class StreamingSpeechChunker {
    /** Minimum number of letters or digits a phrase needs before it is spoken alone. Avoids choppy speech. */
    public static final int MIN_CHUNK_CHARS = 12;
    /** Same limit for scripts written without spaces, where a few characters already make a phrase. */
    public static final int MIN_CHUNK_CHARS_CJK = 4;

    // punctuation that closes a phrase when it is followed by a space
    private static final String SPACED_BOUNDARIES = ".,;:!?।॥،؛؟۔…";
    // punctuation that closes a phrase by itself (scripts written without spaces)
    private static final String UNSPACED_BOUNDARIES = "。，、；：！？";

    private long currentResultId = Long.MIN_VALUE;
    private String spokenPrefix = "";
    private boolean spokeSomething = false;

    /**
     * @param resultId the id of the translation result this text belongs to
     * @param text the whole translated text so far (not only the new part)
     * @param isFinal true if this is the complete translation
     * @return the next phrase to speak, or null if there is nothing to speak for now
     */
    public synchronized String nextChunk(long resultId, String text, boolean isFinal) {
        if (resultId != currentResultId) {
            currentResultId = resultId;
            spokenPrefix = "";
            spokeSomething = false;
        }
        if (text == null) {
            return null;
        }
        if (isFinal) {
            String rest = remainderOfFinal(text);
            spokenPrefix = text;
            if (countSpeakable(rest) == 0) {
                return null;
            }
            spokeSomething = true;
            return rest.trim();
        }
        if (!text.startsWith(spokenPrefix)) {
            // the text was revised behind what we already spoke: wait for the final result
            return null;
        }
        int boundaryEnd = lastSafeBoundary(text, spokenPrefix.length());
        if (boundaryEnd == -1) {
            return null;
        }
        String chunk = text.substring(spokenPrefix.length(), boundaryEnd);
        if (!isLongEnough(chunk)) {
            return null;
        }
        spokenPrefix = text.substring(0, boundaryEnd);
        spokeSomething = true;
        return chunk.trim();
    }

    /** @return true if at least one phrase of the current result has been released. */
    public synchronized boolean hasSpokenSomething() {
        return spokeSomething;
    }

    public synchronized void reset() {
        currentResultId = Long.MIN_VALUE;
        spokenPrefix = "";
        spokeSomething = false;
    }

    private String remainderOfFinal(String finalText) {
        if (spokenPrefix.isEmpty()) {
            return finalText;
        }
        if (finalText.startsWith(spokenPrefix)) {
            return finalText.substring(spokenPrefix.length());
        }
        // The final text differs from what was spoken (for example after a text correction).
        // Skip what was already spoken as closely as we can, then continue from the next word.
        int common = 0;
        int max = Math.min(spokenPrefix.length(), finalText.length());
        while (common < max && Character.toLowerCase(spokenPrefix.charAt(common)) == Character.toLowerCase(finalText.charAt(common))) {
            common++;
        }
        int start = Math.min(spokenPrefix.length(), finalText.length());
        if (common < start) {
            // move forward to the start of the next word so we never begin in the middle of one
            while (start < finalText.length() && !Character.isWhitespace(finalText.charAt(start))) {
                start++;
            }
        }
        return finalText.substring(start);
    }

    /**
     * @return the index right after the last phrase boundary that is safe to speak up to, or -1 if there is none
     */
    private static int lastSafeBoundary(String text, int from) {
        for (int i = text.length() - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (UNSPACED_BOUNDARIES.indexOf(c) != -1) {
                // safe once at least one more character follows
                if (i + 1 < text.length()) {
                    return i + 1;
                }
            } else if (SPACED_BOUNDARIES.indexOf(c) != -1) {
                // safe once a space and the start of the next word follow, this also skips "3.5" and "1,000"
                if (i + 2 < text.length() && Character.isWhitespace(text.charAt(i + 1)) && hasSpeakableFrom(text, i + 2)) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static boolean hasSpeakableFrom(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLongEnough(String chunk) {
        int speakable = countSpeakable(chunk);
        if (speakable == 0) {
            return false;
        }
        return speakable >= (containsUnspacedScript(chunk) ? MIN_CHUNK_CHARS_CJK : MIN_CHUNK_CHARS);
    }

    private static int countSpeakable(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean containsUnspacedScript(String text) {
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.THAI) {
                return true;
            }
        }
        return false;
    }
}
