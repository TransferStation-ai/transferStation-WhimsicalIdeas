package transferstation.transferstation_whimsicalideas.client.voice;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Captures microphone audio using Java Sound API.
 * Push-to-talk: call startRecording() to begin, stopRecording() to finish.
 * The resulting WAV bytes are passed to the callback for transcription.
 */
public class VoiceCaptureService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float SAMPLE_RATE = 16000.0f;
    private static final int SAMPLE_BITS = 16;
    private static final int CHANNELS = 1;

    private static TargetDataLine line;
    private static Thread captureThread;
    private static volatile boolean recording = false;
    private static volatile boolean available = false;
    private static Consumer<byte[]> onRecordingComplete;

    /**
     * Check if a microphone is available on this system.
     */
    public static boolean isMicrophoneAvailable() {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixers) {
            Mixer mixer = AudioSystem.getMixer(info);
            Line.Info[] targetLines = mixer.getTargetLineInfo();
            for (Line.Info li : targetLines) {
                if (li.getLineClass() == TargetDataLine.class) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Initialize: check and open the default microphone line.
     * Called once during mod client setup.
     */
    public static void initialize() {
        if (available) return;
        if (!isMicrophoneAvailable()) {
            LOGGER.warn("[VoiceCapture] No microphone found on this system");
            available = false;
            return;
        }
        available = true;
        LOGGER.info("[VoiceCapture] Microphone is available");
    }

    public static boolean isAvailable() { return available; }
    public static boolean isRecording() { return recording; }

    /**
     * Start recording from the default microphone.
     * The callback receives the complete WAV byte[] when stopRecording() is called.
     */
    public static void startRecording(Consumer<byte[]> onDone) {
        if (!available || recording) return;
        onRecordingComplete = onDone;

        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                LOGGER.warn("[VoiceCapture] Line format not supported: {} Hz {} bit {} ch",
                        SAMPLE_RATE, SAMPLE_BITS, CHANNELS);
                available = false;
                return;
            }

            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            recording = true;

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            captureThread = new Thread(() -> {
                byte[] chunk = new byte[4096];
                try {
                    while (recording && line.isOpen()) {
                        int bytesRead = line.read(chunk, 0, chunk.length);
                        if (bytesRead > 0) {
                            buffer.write(chunk, 0, bytesRead);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("[VoiceCapture] Error during recording", e);
                } finally {
                    byte[] audioData = buffer.toByteArray();
                    if (onRecordingComplete != null && audioData.length > 0) {
                        byte[] wavData = createWavFile(audioData, (int) SAMPLE_RATE);
                        onRecordingComplete.accept(wavData);
                    }
                }
            }, "VoiceCapture");
            captureThread.setDaemon(true);
            captureThread.start();

        } catch (LineUnavailableException e) {
            LOGGER.error("[VoiceCapture] Failed to open microphone line", e);
            available = false;
        }
    }

    /**
     * Stop recording. Closes the line and signals the capture thread to finish.
     */
    public static void stopRecording() {
        recording = false;
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
        if (captureThread != null && captureThread.isAlive()) {
            try {
                captureThread.join(2000);
            } catch (InterruptedException ignored) {
                captureThread.interrupt();
            }
            captureThread = null;
        }
    }

    /**
     * Creates a WAV byte array from raw PCM data.
     */
    private static byte[] createWavFile(byte[] pcmData, int sampleRate) {
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        int dataSize = pcmData.length;
        int channels = CHANNELS;
        int bitsPerSample = SAMPLE_BITS;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        try {
            // RIFF header
            wav.write("RIFF".getBytes());
            writeInt(wav, 36 + dataSize);
            wav.write("WAVE".getBytes());

            // fmt chunk
            wav.write("fmt ".getBytes());
            writeInt(wav, 16);                  // chunk size
            writeShort(wav, 1);                  // PCM format
            writeShort(wav, channels);           // channels
            writeInt(wav, sampleRate);           // sample rate
            writeInt(wav, byteRate);             // byte rate
            writeShort(wav, blockAlign);         // block align
            writeShort(wav, bitsPerSample);      // bits per sample

            // data chunk
            wav.write("data".getBytes());
            writeInt(wav, dataSize);
            wav.write(pcmData);
        } catch (IOException e) {
            LOGGER.error("[VoiceCapture] Failed to build WAV", e);
            return pcmData; // fallback: return raw PCM
        }
        return wav.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /** Clean up resources. Call on mod shutdown. */
    public static void shutdown() {
        if (recording) stopRecording();
        available = false;
    }
}
