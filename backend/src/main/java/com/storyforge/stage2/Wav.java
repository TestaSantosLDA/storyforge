package com.storyforge.stage2;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Minimal 16-bit PCM WAV handling for stitching narration: read the sample data, write silence, write a file.
 * Every clip comes from the same engine at the same rate, which is checked.
 */
final class Wav {

    record Pcm(int sampleRate, int channels, byte[] data) {
        double seconds() {
            return data.length / (2.0 * channels * sampleRate);
        }
    }

    private Wav() {
    }

    static Pcm read(byte[] wav) {
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) {
            throw new IllegalArgumentException("not a WAV file");
        }
        int pos = 12;
        int rate = 0;
        int channels = 0;
        int bits = 0;
        while (pos + 8 <= wav.length) {
            int id = b.getInt(pos);
            int size = b.getInt(pos + 4);
            if (id == 0x20746d66) { // "fmt "
                channels = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22);
            } else if (id == 0x61746164) { // "data"
                if (bits != 16) {
                    throw new IllegalArgumentException("expected 16-bit PCM, got " + bits);
                }
                int len = Math.min(size, wav.length - pos - 8);
                byte[] data = new byte[len];
                System.arraycopy(wav, pos + 8, data, 0, len);
                return new Pcm(rate, channels, data);
            }
            pos += 8 + size + (size & 1);
        }
        throw new IllegalArgumentException("WAV has no data chunk");
    }

    static byte[] silence(int sampleRate, int channels, double seconds) {
        return new byte[(int) Math.round(seconds * sampleRate) * channels * 2];
    }

    static byte[] write(int sampleRate, int channels, List<byte[]> parts) {
        int len = parts.stream().mapToInt(p -> p.length).sum();
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x46464952).putInt(36 + len).putInt(0x45564157);
        h.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) channels).putInt(sampleRate)
                .putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.putInt(0x61746164).putInt(len);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + len);
        out.writeBytes(h.array());
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }
}
