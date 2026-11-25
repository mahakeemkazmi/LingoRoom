package nie.translator.rtranslator.voice_translation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StreamingSpeechChunkerTest {

    /** Feeds the text one character at a time, as a stream of growing partial results, then as the final result. */
    private static List<String> stream(String full) {
        StreamingSpeechChunker chunker = new StreamingSpeechChunker();
        List<String> spoken = new ArrayList<>();
        for (int i = 1; i <= full.length(); i++) {
            String chunk = chunker.nextChunk(1, full.substring(0, i), false);
            if (chunk != null) spoken.add(chunk);
        }
        String last = chunker.nextChunk(1, full, true);
        if (last != null) spoken.add(last);
        return spoken;
    }

    @Test
    public void speaksEachPhraseAsSoonAsItIsComplete() {
        assertEquals(Arrays.asList("Hello there my friend,", "how are you doing today?", "I am fine."),
                stream("Hello there my friend, how are you doing today? I am fine."));
    }

    @Test
    public void doesNotSpeakVeryShortPhrasesAlone() {
        assertEquals(Arrays.asList("Yes, I will come to the office tomorrow morning."),
                stream("Yes, I will come to the office tomorrow morning."));
    }

    @Test
    public void doesNotSplitInsideNumbers() {
        assertEquals(Arrays.asList("The price is 1,000.50 rupees for everything,", "including delivery."),
                stream("The price is 1,000.50 rupees for everything, including delivery."));
    }

    @Test
    public void textWithoutPunctuationIsSpokenOnceAtTheEnd() {
        assertEquals(Arrays.asList("where is the nearest bus stop"), stream("where is the nearest bus stop"));
    }

    @Test
    public void splitsOnHindiAndUrduPunctuation() {
        assertEquals(Arrays.asList("मैं कल सुबह दफ्तर आऊंगा।", "आप कैसे हैं?"),
                stream("मैं कल सुबह दफ्तर आऊंगा। आप कैसे हैं?"));
        assertEquals(2, stream("میں کل صبح دفتر آؤں گا، آپ کیسے ہیں؟").size());
    }

    @Test
    public void finalTextThatWasCorrectedIsNotSpokenTwice() {
        StreamingSpeechChunker chunker = new StreamingSpeechChunker();
        assertNull(chunker.nextChunk(7, "hello there my friend,", false));
        assertEquals("hello there my friend,", chunker.nextChunk(7, "hello there my friend, how", false));
        assertEquals("how are you?", chunker.nextChunk(7, "Hello there my friend, how are you?", true));
    }

    @Test
    public void aNewResultStartsFromTheBeginning() {
        StreamingSpeechChunker chunker = new StreamingSpeechChunker();
        assertEquals("First sentence here.", chunker.nextChunk(1, "First sentence here.", true));
        assertEquals("Second sentence here.", chunker.nextChunk(2, "Second sentence here.", true));
    }

    @Test
    public void nothingIsSpokenForTextWithoutWords() {
        assertNull(new StreamingSpeechChunker().nextChunk(3, "...", true));
    }
}
