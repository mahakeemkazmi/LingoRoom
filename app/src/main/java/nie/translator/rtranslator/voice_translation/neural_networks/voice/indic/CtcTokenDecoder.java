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

package nie.translator.rtranslator.voice_translation.neural_networks.voice.indic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the frame by frame output of a CTC speech model into text (greedy decoding).
 * The token file has one "token id" pair per line, as used by sherpa-onnx.
 */
public class CtcTokenDecoder {
    private static final char WORD_START = '▁';   // the SentencePiece marker for the start of a word
    private final String[] tokens;
    private final int blankId;

    public CtcTokenDecoder(InputStream tokensFile) throws IOException {
        List<String> list = new ArrayList<>();
        int blank = -1;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tokensFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                int split = line.lastIndexOf(' ');
                if (split <= 0) continue;
                String token = line.substring(0, split);
                int id = Integer.parseInt(line.substring(split + 1).trim());
                while (list.size() <= id) list.add("");
                list.set(id, token);
                if (token.equals("<blk>") || token.equals("<blank>")) blank = id;
            }
        }
        tokens = list.toArray(new String[0]);
        blankId = blank != -1 ? blank : tokens.length - 1;   // NeMo puts the blank last
    }

    public int getVocabularySize() {
        return tokens.length;
    }

    public int getBlankId() {
        return blankId;
    }

    /**
     * @param bestIds the most likely token id of every output frame
     */
    public String decode(int[] bestIds) {
        StringBuilder text = new StringBuilder();
        int previous = -1;
        for (int id : bestIds) {
            if (id != previous && id != blankId && id >= 0 && id < tokens.length) {
                text.append(tokens[id]);
            }
            previous = id;
        }
        return text.toString().replace(WORD_START, ' ').replaceAll("\\s+", " ").trim();
    }
}
