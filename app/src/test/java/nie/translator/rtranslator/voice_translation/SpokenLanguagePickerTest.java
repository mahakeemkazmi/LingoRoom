package nie.translator.rtranslator.voice_translation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpokenLanguagePickerTest {

    @Test
    public void decisiveAudioWinsOverTranscriptionConfidence() {
        // "kal subha": the audio says the second language (Urdu), even if the English transcription looks more confident
        assertFalse(SpokenLanguagePicker.isFirstLanguage(0.1, -0.2, -0.9));
        assertTrue(SpokenLanguagePicker.isFirstLanguage(0.9, -0.9, -0.2));
    }

    @Test
    public void undecidedAudioFallsBackToConfidence() {
        assertTrue(SpokenLanguagePicker.isFirstLanguage(0.5, -0.2, -0.9));
        assertFalse(SpokenLanguagePicker.isFirstLanguage(0.55, -0.9, -0.2));
    }

    @Test
    public void unknownAudioFallsBackToConfidence() {
        assertTrue(SpokenLanguagePicker.isFirstLanguage(-1, -0.2, -0.9));
        assertFalse(SpokenLanguagePicker.isFirstLanguage(-1, -0.9, -0.2));
        assertTrue(SpokenLanguagePicker.isFirstLanguage(Double.NaN, -0.2, -0.2));
    }
}
