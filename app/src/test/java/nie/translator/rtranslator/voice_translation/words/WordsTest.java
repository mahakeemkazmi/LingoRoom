package nie.translator.rtranslator.voice_translation.words;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class WordsTest {
    private final WordMatcher matcher = new WordMatcher(Arrays.asList("Hyderabad", "\u062D\u06CC\u062F\u0631\u0622\u0628\u0627\u062F", "Abdul Hakeem", "Maitreyi", "Adelaide"));

    @Test
    public void closeSpellingsBecomeTheKnownWord() {
        assertEquals("I am from Hyderabad, India.", matcher.fix("I am from Hydrabad, India."));
        assertEquals("My name is Abdul Hakeem.", matcher.fix("My name is Abdul Hakim."));
        assertEquals("I live in Adelaide now", matcher.fix("I live in Adelade now"));
        assertEquals("We went to Hyderabad yesterday", matcher.fix("We went to Hyder abad yesterday"));
    }

    @Test
    public void otherWordsAreLeftAlone() {
        assertEquals("I already had breakfast", matcher.fix("I already had breakfast"));
        assertEquals("The maitre d was kind", matcher.fix("The maitre d was kind"));
        assertEquals("Abdul said hello", matcher.fix("Abdul said hello"));
    }

    @Test
    public void urduNameHeardInPiecesIsJoinedWithoutEatingTheNextWord() {
        String heard = "\u0645\u06CC\u06BA \u062D\u06CC\u062F\u0631 \u0622\u0628\u0627\u062F \u062C\u0627 \u0631\u06C1\u0627 \u06C1\u0648\u06BA";
        String fixed = "\u0645\u06CC\u06BA \u062D\u06CC\u062F\u0631\u0622\u0628\u0627\u062F \u062C\u0627 \u0631\u06C1\u0627 \u06C1\u0648\u06BA";
        assertEquals(fixed, matcher.fix(heard));
    }

    @Test
    public void deccaniWordsBecomeStandard() {
        // "mereku ... nakko" and a joined continuous verb, in Devanagari
        assertEquals("\u092E\u0941\u091D\u0947 \u092A\u093E\u0928\u0940 \u0926\u094B, \u0928\u0939\u0940\u0902 \u092C\u094B\u0932\u094B",
                DeccaniNormalizer.normalize("\u092E\u0947\u0930\u0947\u0915\u0941 \u092A\u093E\u0928\u0940 \u0926\u094B, \u0928\u0915\u094D\u0915\u094B \u092C\u094B\u0932\u094B"));
        // "jaari" is an ordinary word and stays
        String ongoing = "\u092F\u0939 \u0915\u093E\u092E \u091C\u093E\u0930\u0940 \u0939\u0948";
        assertEquals(ongoing, DeccaniNormalizer.normalize(ongoing));
    }
}
