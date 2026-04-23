package dev.endcity.network.compression;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RleZlibCompressorTest {

    @Test
    void rleEncode_literalsAndShortRuns() {
        assertArrayEquals(
                new byte[] { 1, 2, 2, 3, 3, 3 },
                RleZlibCompressor.rleEncode(new byte[] { 1, 2, 2, 3, 3, 3 }));
    }

    @Test
    void rleEncode_emptyInput_isEmpty() {
        assertArrayEquals(new byte[0], RleZlibCompressor.rleEncode(new byte[0]));
    }

    @Test
    void rleEncode_nullInputRejected() {
        assertThrows(NullPointerException.class, () -> RleZlibCompressor.rleEncode(null));
    }

    @Test
    void rleEncode_literalFfUsesEscapedShortRun() {
        assertArrayEquals(
                new byte[] { (byte) 0xFF, 0x02 },
                RleZlibCompressor.rleEncode(new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF }));
    }

    @Test
    void rleEncode_longRunUsesRunToken() {
        assertArrayEquals(
                new byte[] { (byte) 0xFF, 0x03, 0x2A },
                RleZlibCompressor.rleEncode(new byte[] { 0x2A, 0x2A, 0x2A, 0x2A }));
    }

    @Test
    void compress_wrapsRleStreamInZlib() throws DataFormatException {
        byte[] raw = new byte[] { 7, 7, 7, 7, 3, 2, (byte) 0xFF, (byte) 0xFF };
        byte[] inflated = inflate(RleZlibCompressor.compress(raw));

        assertArrayEquals(RleZlibCompressor.rleEncode(raw), inflated);
    }

    static byte[] inflate(byte[] compressed) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        byte[] tmp = new byte[256];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!inflater.finished()) {
            int n = inflater.inflate(tmp);
            if (n == 0 && inflater.needsInput()) {
                break;
            }
            out.write(tmp, 0, n);
        }
        inflater.end();
        return out.toByteArray();
    }
}
