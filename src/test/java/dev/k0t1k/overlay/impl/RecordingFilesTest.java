package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Output names and command lines of the recording code that need no ffmpeg to check. */
class RecordingFilesTest {

    @Test
    void reservedNamesNeverCollide(@TempDir Path folder) throws IOException {
        Path first = Replay.reserve(folder, "clip", "mkv");
        Path second = Replay.reserve(folder, "clip", "mkv");
        Path third = Replay.reserve(folder, "clip", "mkv");
        assertEquals("clip.mkv", first.getFileName().toString());
        assertEquals("clip_2.mkv", second.getFileName().toString());
        assertEquals("clip_3.mkv", third.getFileName().toString());
        assertNotEquals(first, second);
    }

    @Test
    void webpCommandUsesOnlyEncoderOptions() {
        List<String> command = Ffmpeg.webpArgs("ffmpeg", Paths.get("in.mkv"), Paths.get("out.webp"), 10, 30, 960, 80, false);
        assertTrue(command.containsAll(Arrays.asList("libwebp_anim", "-sseof", "-10", "-quality", "80", "-compression_level", "4")));
        assertEquals("fps=30,scale='min(960,iw)':-2:flags=lanczos", command.get(command.indexOf("-vf") + 1));
        assertEquals("out.webp", command.get(command.size() - 1));
        List<String> source = Ffmpeg.webpArgs("ffmpeg", Paths.get("in.mkv"), Paths.get("out.webp"), 5, 15, 0, 100, true);
        assertEquals("fps=15", source.get(source.indexOf("-vf") + 1));
        assertEquals("1", source.get(source.indexOf("-lossless") + 1));
    }

    @Test
    void invalidFolderPathsAreRejected() {
        assertTrue(Options.validFolder(""));
        assertTrue(Options.validFolder("/tmp/clips"));
        assertFalse(Options.validFolder("bad\0path"));
    }
}
