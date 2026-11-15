import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates the bundled two-note simulation warning sound with only JDK 17. */
public final class GenerateChime {
    private static final int RATE = 22_050;
    private static final double[][] NOTES = {{880.0, 0.20}, {659.25, 0.28}};

    private GenerateChime() {}

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0
            ? Path.of("app", "src", "main", "res", "raw", "alert_chime.wav")
            : Path.of(args[0]);
        int silence = (int) (RATE * 0.045);
        int frames = silence * NOTES.length;
        for (double[] note : NOTES) frames += (int) (RATE * note[1]);

        ByteBuffer wav = ByteBuffer.allocate(44 + frames * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        putAscii(wav, "RIFF");
        wav.putInt(36 + frames * Short.BYTES);
        putAscii(wav, "WAVEfmt ");
        wav.putInt(16); // PCM format chunk size
        wav.putShort((short) 1); // PCM
        wav.putShort((short) 1); // mono
        wav.putInt(RATE);
        wav.putInt(RATE * Short.BYTES);
        wav.putShort((short) Short.BYTES);
        wav.putShort((short) 16);
        putAscii(wav, "data");
        wav.putInt(frames * Short.BYTES);

        for (double[] note : NOTES) {
            double frequency = note[0];
            double duration = note[1];
            int count = (int) (RATE * duration);
            for (int i = 0; i < count; i++) {
                double time = (double) i / RATE;
                double envelope = Math.min(1.0, time / 0.012) * Math.exp(-7.0 * time / duration);
                double value = 0.44 * envelope * Math.sin(2.0 * Math.PI * frequency * time);
                wav.putShort((short) (value * 32767));
            }
            for (int i = 0; i < silence; i++) wav.putShort((short) 0);
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(output, wav.array());
        System.out.println(output.toAbsolutePath());
    }

    private static void putAscii(ByteBuffer buffer, String text) {
        for (int i = 0; i < text.length(); i++) buffer.put((byte) text.charAt(i));
    }
}
