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
 * Decides which of two languages was spoken when the text of the two transcriptions does not settle it.
 * <br /><br />
 * This happens mostly with short phrases: "kal subha" transcribed as English gives "Kal Subhe", which a text
 * language detector cannot tell apart from real English. The sound of the audio is a much better clue there,
 * so it is used first, and the transcription confidence only when the sound is not decisive.
 */
public class SpokenLanguagePicker {
    /** How sure the audio must be about one language before we trust it over the transcription confidence. */
    public static final double AUDIO_DECISIVE_PROBABILITY = 0.6;

    /**
     * @param firstLanguageAudioProbability how likely the first language is from the sound alone (0 to 1), or a negative value if unknown
     * @param firstConfidence confidence of the transcription made as the first language
     * @param secondConfidence confidence of the transcription made as the second language
     * @return true if the first language should be used, false for the second
     */
    public static boolean isFirstLanguage(double firstLanguageAudioProbability, double firstConfidence, double secondConfidence) {
        boolean audioKnown = firstLanguageAudioProbability >= 0 && firstLanguageAudioProbability <= 1;
        if (audioKnown) {
            if (firstLanguageAudioProbability >= AUDIO_DECISIVE_PROBABILITY) {
                return true;
            }
            if (firstLanguageAudioProbability <= 1 - AUDIO_DECISIVE_PROBABILITY) {
                return false;
            }
        }
        return firstConfidence >= secondConfidence;
    }
}
