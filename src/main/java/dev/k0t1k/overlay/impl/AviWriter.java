package dev.k0t1k.overlay.impl;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes JPEG frames as a Motion-JPEG AVI, a video every player opens (VLC, the Windows player, mpv)
 * without an encoder: the frames are stored as they are, with a RIFF header and an index.
 */
final class AviWriter {
    private AviWriter() {
    }

    static void write(Path file, int width, int height, int fps, List<byte[]> frames) throws IOException {
        int moviSize = 4;
        int largest = 0;
        for (byte[] frame : frames) {
            moviSize += 8 + padded(frame.length);
            largest = Math.max(largest, frame.length);
        }
        int hdrlSize = 4 + (8 + 56) + (8 + 4 + (8 + 56) + (8 + 40));
        int idxSize = frames.size() * 16;
        int riffSize = 4 + (8 + hdrlSize) + (8 + moviSize) + (8 + idxSize);

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            ByteBuffer b = ByteBuffer.allocate(8 + 12 + hdrlSize + 12).order(ByteOrder.LITTLE_ENDIAN);
            fourcc(b, "RIFF");
            b.putInt(riffSize);
            fourcc(b, "AVI ");
            fourcc(b, "LIST");
            b.putInt(hdrlSize);
            fourcc(b, "hdrl");
            // Main AVI header.
            fourcc(b, "avih");
            b.putInt(56);
            b.putInt(1_000_000 / fps); // microseconds per frame
            b.putInt(largest * fps); // max bytes per second
            b.putInt(0); // padding granularity
            b.putInt(0x10); // AVIF_HASINDEX
            b.putInt(frames.size());
            b.putInt(0); // initial frames
            b.putInt(1); // streams
            b.putInt(largest);
            b.putInt(width);
            b.putInt(height);
            b.putInt(0).putInt(0).putInt(0).putInt(0);
            // The one video stream.
            fourcc(b, "LIST");
            b.putInt(4 + (8 + 56) + (8 + 40));
            fourcc(b, "strl");
            fourcc(b, "strh");
            b.putInt(56);
            fourcc(b, "vids");
            fourcc(b, "MJPG");
            b.putInt(0); // flags
            b.putShort((short) 0).putShort((short) 0); // priority, language
            b.putInt(0); // initial frames
            b.putInt(1); // scale
            b.putInt(fps); // rate: fps frames per second
            b.putInt(0); // start
            b.putInt(frames.size()); // length
            b.putInt(largest);
            b.putInt(-1); // quality
            b.putInt(0); // sample size
            b.putShort((short) 0).putShort((short) 0).putShort((short) width).putShort((short) height);
            fourcc(b, "strf");
            b.putInt(40); // BITMAPINFOHEADER
            b.putInt(40);
            b.putInt(width);
            b.putInt(height);
            b.putShort((short) 1);
            b.putShort((short) 24);
            fourcc(b, "MJPG");
            b.putInt(width * height * 3);
            b.putInt(0).putInt(0).putInt(0).putInt(0);
            fourcc(b, "LIST");
            b.putInt(moviSize);
            fourcc(b, "movi");
            out.write(b.array(), 0, b.position());

            ByteBuffer chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            for (byte[] frame : frames) {
                chunk.clear();
                fourcc(chunk, "00dc");
                chunk.putInt(frame.length);
                out.write(chunk.array());
                out.write(frame);
                if ((frame.length & 1) != 0) {
                    out.write(0);
                }
            }

            ByteBuffer index = ByteBuffer.allocate(8 + idxSize).order(ByteOrder.LITTLE_ENDIAN);
            fourcc(index, "idx1");
            index.putInt(idxSize);
            int offset = 4; // offsets count from the "movi" fourcc
            for (byte[] frame : frames) {
                fourcc(index, "00dc");
                index.putInt(0x10); // AVIIF_KEYFRAME
                index.putInt(offset);
                index.putInt(frame.length);
                offset += 8 + padded(frame.length);
            }
            out.write(index.array());
        }
    }

    private static int padded(int length) {
        return length + (length & 1);
    }

    private static void fourcc(ByteBuffer b, String code) {
        b.put(code.getBytes(StandardCharsets.US_ASCII));
    }
}
