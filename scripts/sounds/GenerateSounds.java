import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the placeholder alarm sounds (ADR 0061, ADR 0065). Run from the repository root with a JDK, no dependency:
 *
 *   java scripts/sounds/GenerateSounds.java android/src/main/res/raw
 *
 * It writes two files, each 16 bit mono PCM at 22050 Hz and exactly 2.0 seconds:
 *
 *   ring_primary.wav  three short 880 Hz beeps and a pause, at 60 percent of full scale;
 *   ring_backup.wav   five beeps alternating 880 Hz and 1320 Hz, at full scale. The backup sound is the louder one:
 *                     it plays after the unacknowledged interval, when the primary has not been answered. It is
 *                     louder in the file (about 4.4 dB at the same player volume), because the app never changes
 *                     the system alarm stream volume.
 *
 * Both are synthetic tones made by this script alone, so they have no author, no licence and no source to credit.
 * They are placeholders: Shubham chooses the final sounds. They are loopable because each holds a whole number of
 * samples, starts and ends in silence, and every beep fades in and out over 10 ms, so there is no click at the
 * seam. They are far under the 30 second limit.
 */
public final class GenerateSounds {
    private static final int RATE = 22050;
    private static final double SECONDS = 2.0;
    private static final double FREQUENCY = 880.0;
    private static final double BEEP = 0.25;
    private static final double GAP = 0.15;
    private static final double FADE = 0.010;
    private static final double PRIMARY_AMPLITUDE = 0.6;
    private static final double BACKUP_AMPLITUDE = 1.0;

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args.length > 0 ? args[0] : "android/src/main/res/raw");
        Files.createDirectories(directory);
        Files.write(directory.resolve("ring_primary.wav"), wav(samples(3, 0.1, BEEP, GAP, PRIMARY_AMPLITUDE, FREQUENCY)));
        Files.write(
            directory.resolve("ring_backup.wav"),
            wav(samples(5, 0.05, 0.2, 0.1, BACKUP_AMPLITUDE, FREQUENCY, FREQUENCY * 1.5)));
    }

    /** [beeps] beeps of [beepSeconds], [gapSeconds] apart from [offset], each at [amplitude], the beeps taking [frequencies] in turn. */
    private static short[] samples(
            int beeps, double offset, double beepSeconds, double gapSeconds, double amplitude, double... frequencies) {
        int total = (int) Math.round(RATE * SECONDS);
        short[] out = new short[total];
        for (int beep = 0; beep < beeps; beep++) {
            int start = (int) Math.round(RATE * (offset + beep * (beepSeconds + gapSeconds)));
            int length = (int) Math.round(RATE * beepSeconds);
            for (int i = 0; i < length; i++) {
                double t = i / (double) RATE;
                double fade = Math.min(1.0, Math.min(t, beepSeconds - t) / FADE);
                double frequency = frequencies[beep % frequencies.length];
                out[start + i] = (short) Math.round(Short.MAX_VALUE * amplitude * fade * Math.sin(2 * Math.PI * frequency * t));
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
