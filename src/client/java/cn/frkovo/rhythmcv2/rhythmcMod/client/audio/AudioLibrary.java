package cn.frkovo.rhythmcv2.rhythmcMod.client.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioInputStream;

/**
 * 本地音频库（§11.3 CharterMod 提案布局）：.minecraft/rhythmc-audio/&lt;song_folder&gt;/。
 * 匹配顺序：audioHint 精确名 → 首选名（audio/song/music/bgm/track/preview）→ 目录内首个音频。
 * （注意与 ChartMaker 的 .minecraft/projects/&lt;folder&gt;/ 布局不通用，双工具需各放一份。）
 */
public final class AudioLibrary {

    private static final String[] PREFERRED_NAMES = {
            "audio", "song", "music", "bgm", "track", "preview"
    };
    private static final String[] EXTENSIONS = {
            ".ogg", ".mp3", ".flac", ".wav", ".aif", ".aiff", ".au"
    };

    private AudioLibrary() {
    }

    public static Path root() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("rhythmc-audio");
    }

    public record Match(Path file, String sha1, long lengthMs, String reason) {
    }

    /** 匹配失败返回 reason 非空的 Match（file=null）。 */
    public static Match resolve(String songFolder, String songName, String audioHint) {
        Path dir = root().resolve(sanitize(songFolder));
        if (!Files.isDirectory(dir)) {
            return new Match(null, "", 0, "本地音频库缺少目录 rhythmc-audio/" + sanitize(songFolder));
        }
        List<Path> candidates = new ArrayList<>();
        if (audioHint != null && !audioHint.isBlank()) {
            Path hinted = dir.resolve(audioHint.trim());
            if (Files.isRegularFile(hinted)) {
                candidates.add(hinted);
            }
        }
        for (String name : PREFERRED_NAMES) {
            for (String ext : EXTENSIONS) {
                Path p = dir.resolve(name + ext);
                if (Files.isRegularFile(p)) {
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) {
            try (var stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(AudioLibrary::hasAudioExtension)
                        .sorted()
                        .forEach(candidates::add);
            } catch (IOException ignored) {
            }
        }
        if (candidates.isEmpty()) {
            return new Match(null, "", 0, "目录内无音频文件");
        }
        Path chosen = candidates.getFirst();
        try {
            long lengthMs = probeLengthMs(chosen);
            return new Match(chosen, sha1Of(chosen), lengthMs, "");
        } catch (Exception e) {
            return new Match(null, "", 0, "音频读取失败: " + e.getMessage());
        }
    }

    private static boolean hasAudioExtension(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitize(String folder) {
        String s = (folder == null ? "" : folder).trim();
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "untitled" : sb.toString();
    }

    static long probeLengthMs(Path file) throws Exception {
        try (AudioInputStream in = AudioStreamHelper.openDecodedStream(file)) {
            return (long) (in.getFrameLength() / in.getFormat().getFrameRate() * 1000.0);
        }
    }

    static String sha1Of(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return ""; // SHA-1 为空串表示未计算（附录 B）
        }
    }
}
