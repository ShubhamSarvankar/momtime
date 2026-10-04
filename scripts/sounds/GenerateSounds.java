import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the placeholder alarm sound (ADR 0061). Run from the repository root with a JDK, no dependency:
 *
 *   java scripts/sounds/GenerateSounds.java android/src/main/res/raw
 *
 * It writes ring_primary.wav: 16 bit mono PCM at 22050 Hz, exactly 2.0 seconds, three short 880 Hz beeps and a
 * pause. It is a synthetic tone made by this script alone, so it has no author, no licence and no source to
 * credit. It is a placeholder: Shubham chooses the final sounds. It is loopable because it holds a whole number
 * of samples, starts and ends in silence, and every beep fades in and out over 10 ms, so there is no click at the
 * seam. It is far under the 30 second limit.
 */
public final class GenerateSounds {
    private static final int RATE = 22050;
    private static final double SECONDS = 2.0;
    private static final double FREQUENCY = 880.0;
    private static final double BEEP = 0.25;
    private static final double GAP = 0.15;
    private static final double FADE = 0.010;
    private static final double AMPLITUDE = 0.6;

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args.length > 0 ? args[0] : "android/src/main/res/raw");
        Files.createDirectories(directory);
        Files.write(directory.resolve("ring_primary.wav"), wav(samples()));
    }

    private static short[] samples() {
        int total = (int) Math.round(RATE * SECONDS);
        short[] out = new short[total];
        for (int beep = 0; beep < 3; beep++) {
            int start = (int) Math.round(RATE * (0.1 + beep * (BEEP + GAP)));
            int length = (int) Math.round(RATE * BEEP);
            for (int i = 0; i < length; i++) {
                double t = i / (double) RATE;
                double fade = Math.min(1.0, Math.min(t, BEEP - t) / FADE);
                out[start + i] = (short) Math.round(Short.MAX_VALUE * AMPLITUDE * fade * Math.sin(2 * Math.PI * FREQUENCY * t));
            }
        }
        return out;
    }

    private static byte[] wav(short[] samples) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        int dataLength = samples.length * 2;
        out.writeBytes("RIFF");
        out.writeInt(Integer.reverseBytes(36 + dataLength));
        out.writeBytes("WAVEfmt ");
        out.writeInt(Integer.reverseBytes(16));
        out.writeShort(Short.reverseBytes((short) 1));
        out.writeShort(Short.reverseBytes((short) 1));
        out.writeInt(Integer.reverseBytes(RATE));
        out.writeInt(Integer.reverseBytes(RATE * 2));
        out.writeShort(Short.reverseBytes((short) 2));
        out.writeShort(Short.reverseBytes((short) 16));
        out.writeBytes("data");
        out.writeInt(Integer.reverseBytes(dataLength));
        for (short sample : samples) {
            out.writeShort(Short.reverseBytes(sample));
        }
        return bytes.toByteArray();
    }
}
