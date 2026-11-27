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

    @Test
    public void deccaniPhrasesFromTheDictionary() {
        assertEquals("\u092E\u0941\u091D\u0947 \u0928\u0939\u0940\u0902 \u092E\u093E\u0932\u0942\u092E", DeccaniNormalizer.normalize("\u092E\u0947\u0930\u0947\u0915\u0941 \u0928\u0908 \u092E\u093E\u0932\u0942\u092E"));
        assertEquals("\u0915\u094D\u092F\u094B\u0902, \u0915\u0939\u093E\u0901 \u091C\u093E \u0930\u0939\u0947", DeccaniNormalizer.normalize("\u0915\u093E\u0907\u0915\u0942 \u0930\u0947, \u0915\u093F\u0927\u0930\u0915\u0941 \u091C\u093E\u0930\u0947"));
        assertEquals("\u0928\u0939\u0940\u0902, \u092C\u0915\u0935\u093E\u0938 \u092C\u093E\u0924\u0947\u0902 \u092E\u0924 \u0915\u0930", DeccaniNormalizer.normalize("\u0928\u0915\u094D\u0915\u094B \u092C\u093E\u092C\u093E, \u092C\u0948\u0917\u0928 \u0915\u0947 \u092C\u093E\u0924\u093E\u0902 \u092E\u0924 \u0915\u0930"));
        assertEquals("\u0910\u0938\u0947 \u0939\u0940 \u092C\u094B\u0932\u093E", DeccaniNormalizer.normalize("\u0910\u0938\u093E\u0907\u091A \u092C\u094B\u0932\u093E"));
        assertEquals("\u0646\u06C1\u06CC\u06BA\u060C \u06A9\u06CC\u0648\u06BA \u062C\u0627 \u0631\u06C1\u06D2", DeccaniNormalizer.normalize("\u0646\u06A9\u0648 \u0628\u0627\u0628\u0627\u060C \u06A9\u0627\u0626\u06CC\u06A9\u0648 \u062C\u0627\u0631\u06D2"));
    }

    @Test
    public void ordinaryWordsThatLookLikeSlangStay() {
        assertEquals("\u0939\u094B \u0917\u092F\u093E, \u092F\u0939 \u0915\u093E\u092E \u091C\u093E\u0930\u0940 \u0939\u0948, \u092C\u0948\u0902\u0917\u0928 \u0915\u0940 \u0938\u092C\u094D\u091C\u093C\u0940", DeccaniNormalizer.normalize("\u0939\u094B \u0917\u092F\u093E, \u092F\u0939 \u0915\u093E\u092E \u091C\u093E\u0930\u0940 \u0939\u0948, \u092C\u0948\u0902\u0917\u0928 \u0915\u0940 \u0938\u092C\u094D\u091C\u093C\u0940"));
        assertEquals("\u0909\u0938\u0928\u0947 \u091A\u093F\u091F\u094D\u0920\u0940 \u092D\u0947\u091C\u093E", DeccaniNormalizer.normalize("\u0909\u0938\u0928\u0947 \u091A\u093F\u091F\u094D\u0920\u0940 \u092D\u0947\u091C\u093E"));
        assertEquals("\u0645\u06CC\u06BA \u0679\u06BE\u06CC\u06A9 \u06C1\u0648 \u06AF\u06CC\u0627", DeccaniNormalizer.normalize("\u0645\u06CC\u06BA \u0679\u06BE\u06CC\u06A9 \u06C1\u0648 \u06AF\u06CC\u0627"));
    }

    @Test
    public void meaningsGivenBySpeakers() {
        assertEquals("\u0915\u094D\u092F\u093E \u0939\u093E\u0932 \u0939\u0948 \u092F\u093E\u0930", DeccaniNormalizer.normalize("\u0915\u094D\u092F\u093E \u092C\u094B\u0932\u0924\u0947 \u092E\u093F\u092F\u093E\u0902"));
        assertEquals("\u0924\u0941\u092E\u094D\u0939\u0947\u0902 \u0938\u092E\u091D \u092E\u0947\u0902 \u0928\u0939\u0940\u0902 \u0906 \u0930\u0939\u093E", DeccaniNormalizer.normalize("\u0924\u0941\u092E\u094D\u0939\u093E\u0930\u0947\u0915\u0941 \u0938\u092E\u091D \u092E\u0947\u0902 \u0928\u0908 \u0906\u0930\u093E"));
        assertEquals("\u092E\u0941\u0938\u0940\u092C\u0924 \u092E\u0947\u0902 \u092A\u0921\u093C \u0917\u092F\u093E", DeccaniNormalizer.normalize("\u0939\u0932\u094D\u0932\u0915 \u0939\u094B\u0917\u092F\u093E"));
        assertEquals("\u0915\u094D\u092F\u093E \u092C\u0915\u0935\u093E\u0938 \u092C\u093E\u0924 \u0915\u0930 \u0930\u0939\u0947", DeccaniNormalizer.normalize("\u0915\u094D\u092F\u093E \u092C\u0948\u0917\u0928 \u091C\u0948\u0938\u093E \u092C\u093E\u0924 \u0915\u0930\u0930\u0947"));
        assertEquals("\u0935\u094B \u092E\u093F\u092F\u093E\u0902 \u092C\u0940\u0935\u0940 \u0939\u0948\u0902", DeccaniNormalizer.normalize("\u0935\u094B \u092E\u093F\u092F\u093E\u0902 \u092C\u0940\u0935\u0940 \u0939\u0948\u0902"));
        assertEquals("\u092F\u0939 \u0917\u093E\u0921\u093C\u0940 \u0928\u0908 \u0939\u0948", DeccaniNormalizer.normalize("\u092F\u0939 \u0917\u093E\u0921\u093C\u0940 \u0928\u0908 \u0939\u0948"));
        assertEquals("\u0906\u092A \u0915\u094D\u092F\u093E \u092C\u094B\u0932\u0924\u0947 \u0939\u0948\u0902", DeccaniNormalizer.normalize("\u0906\u092A \u0915\u094D\u092F\u093E \u092C\u094B\u0932\u0924\u0947 \u0939\u0948\u0902"));
    }
}
