import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

import nie.translator.rtranslator.voice_translation.neural_networks.voice.indic.IndicConformerRecognizer;

/**
 * Command line check of the speech model outside the app:
 *   java AsrCheck model.onnx tokens.txt clip1.wav [clip2.wav ...]
 * Clips must be 16 kHz, mono, 16 bit PCM WAV files.
 */
public class AsrCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: AsrCheck model.onnx tokens.txt clip.wav...");
            System.exit(2);
        }
        try (IndicConformerRecognizer recognizer = new IndicConformerRecognizer(args[0], args[1], 2)) {
            System.out.println("MODEL " + recognizer.describe());
            for (int i = 2; i < args.length; i++) {
                float[] samples = readWav(args[i]);
                long start = System.currentTimeMillis();
                String text = recognizer.recognize(samples);
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("RESULT\t" + args[i] + "\t" + String.format("%.1fs audio, %d ms", samples.length / 16000.0, elapsed) + "\t" + text);
            }
        }
    }

    static float[] readWav(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int position = 12;   // skip RIFF header
        int channels = 1, sampleRate = 16000, bits = 16;
        while (position + 8 <= bytes.length) {
            String id = new String(bytes, position, 4);
            int size = buffer.getInt(position + 4);
            int body = position + 8;
            if (id.equals("fmt ")) {
                channels = buffer.getShort(body + 2);
                sampleRate = buffer.getInt(body + 4);
                bits = buffer.getShort(body + 14);
            } else if (id.equals("data")) {
                if (sampleRate != 16000 || bits != 16) {
                    throw new IOException(path + ": need 16 kHz 16 bit audio, got " + sampleRate + " Hz " + bits + " bit");
                }
                int available = Math.min(size, bytes.length - body);
                if (available < 0) available = bytes.length - body;
                int count = available / (2 * channels);
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) {
                    samples[i] = buffer.getShort(body + i * 2 * channels) / 32768f;
                }
                return samples;
            }
            position = body + size + (size & 1);
        }
        throw new IOException(path + ": no audio data found");
    }
}
