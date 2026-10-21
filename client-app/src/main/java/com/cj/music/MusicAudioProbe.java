package com.cj.music;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;

/** Cheap container check before a local file enters the library or waveform queue. */
final class MusicAudioProbe {
    private MusicAudioProbe() {}

    static boolean readable(File file) {
        if (file == null || !file.isFile() || file.length() == 0) return false;
        String extension = MusicDecoder.ext(file.getName());
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (!matches(extension, mime)) continue;
                extractor.selectTrack(i);
                int capacity = 4096;
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    capacity = Math.max(capacity, Math.min(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 65536));
                return extractor.readSampleData(ByteBuffer.allocate(capacity), 0) > 0;
            }
        } catch (Exception | LinkageError | OutOfMemoryError ignored) {
            return false;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
        return false;
    }

    private static boolean matches(String extension, String mime) {
        if (mime == null || !mime.startsWith("audio/")) return false;
        String type = mime.toLowerCase(Locale.ROOT);
        switch (extension) {
            case "mp3": return type.equals("audio/mpeg") || type.equals("audio/mp3");
            case "flac": return type.contains("flac");
            case "wav": return type.equals("audio/raw") || type.contains("wav") || type.contains("wave") || type.contains("pcm") || type.contains("g711") || type.contains("g722") || type.contains("adpcm");
            case "ogg": case "oga": return type.contains("vorbis") || type.contains("opus") || type.contains("flac");
            case "opus": return type.contains("opus");
            case "amr": return type.contains("amr") || type.equals("audio/3gpp");
            case "aac": return type.contains("aac") || type.contains("mp4a");
            case "m4a": return type.contains("mp4a") || type.contains("alac") || type.contains("aac");
            case "mka": return true;
            default: return false;
        }
    }
}
