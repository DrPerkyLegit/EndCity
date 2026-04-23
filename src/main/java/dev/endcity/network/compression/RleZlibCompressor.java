package dev.endcity.network.compression;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.zip.Deflater;

/**
 * Java port of the Win64 chunk payload compressor used by
 * {@code Compression::CompressLZXRLE}: first apply 4J's byte-RLE transform, then wrap that stream in
 * zlib.
 */
public final class RleZlibCompressor {

    private RleZlibCompressor() {}

    /**
     * Encode {@code raw} with the RLE stream format consumed by the client's LZX-RLE decompressor.
     *
     * <p>RLE symbols:
     * <ul>
     *   <li>{@code 0..254}: literal byte</li>
     *   <li>{@code 255, 0..2}: one to three literal {@code 0xFF} bytes</li>
     *   <li>{@code 255, 3..255, value}: run of {@code count + 1} copies of {@code value}</li>
     * </ul>
     */
    public static byte[] rleEncode(byte[] raw) {
        Objects.requireNonNull(raw, "raw");
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        int i = 0;
        while (i < raw.length) {
            int value = raw[i++] & 0xFF;
            int count = 1;
            while (i < raw.length && (raw[i] & 0xFF) == value && count < 256) {
                i++;
                count++;
            }

            if (count <= 3) {
                if (value == 0xFF) {
                    out.write(0xFF);
                    out.write(count - 1);
                } else {
                    for (int j = 0; j < count; j++) {
                        out.write(value);
                    }
                }
            } else {
                out.write(0xFF);
                out.write(count - 1);
                out.write(value);
            }
        }
        return out.toByteArray();
    }

    /** Apply RLE, then zlib-compress the RLE stream. */
    public static byte[] compress(byte[] raw) {
        byte[] rle = rleEncode(raw);
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(rle);
            deflater.finish();

            byte[] tmp = new byte[Math.max(64, rle.length + 64)];
            ByteArrayOutputStream out = new ByteArrayOutputStream(tmp.length);
            while (!deflater.finished()) {
                int n = deflater.deflate(tmp);
                if (n == 0) {
                    throw new IllegalStateException("deflater made no progress");
                }
                out.write(tmp, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
