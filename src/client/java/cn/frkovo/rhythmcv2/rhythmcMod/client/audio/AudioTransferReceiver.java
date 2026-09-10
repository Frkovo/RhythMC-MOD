package cn.frkovo.rhythmcv2.rhythmcMod.client.audio;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 服务端音频推送接收器：顺序写 .part 临时文件 → 长度/ SHA-256 校验 → 替换为正式文件。
 * 保存路径 = 游戏目录/rhythmc-audio/<songFolder>/<fileName>（与后续 AudioLibrary 布局一致）。
 */
public final class AudioTransferReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger("RhythMC-Charter");

    private String transferId = "";
    private Path targetFile;
    private Path tempFile;
    private OutputStream out;
    private MessageDigest digest;
    private long received;
    private long totalBytes;
    private int nextIndex;
    private String expectedSha256 = "";
    private volatile Path completedFile;
    private volatile String completedSha256 = "";
    private volatile String lastError = "";

    public Path completedFile() {
        return completedFile;
    }

    public String completedSha256() {
        return completedSha256;
    }

    public String lastError() {
        return lastError;
    }

    public boolean start(String transferId, String songFolder, String fileName, long totalBytes, String sha256) {
        reset();
        if (!isSafeName(songFolder) || !isSafeName(fileName)) {
            fail("非法文件名: " + songFolder + "/" + fileName);
            return false;
        }
        if (totalBytes <= 0) {
            fail("文件长度非法");
            return false;
        }
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("rhythmc-audio").resolve(songFolder);
            Files.createDirectories(dir);
            this.transferId = transferId;
            this.targetFile = dir.resolve(fileName);
            this.tempFile = dir.resolve(fileName + ".part");
            this.out = Files.newOutputStream(tempFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            this.digest = MessageDigest.getInstance("SHA-256");
            this.received = 0;
            this.totalBytes = totalBytes;
            this.nextIndex = 0;
            this.expectedSha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
            this.completedFile = null;
            this.completedSha256 = "";
            DownloadProgressState.start(fileName, totalBytes);
            LOGGER.info("[charter_audio] receiving {} ({} bytes)", fileName, totalBytes);
            return true;
        } catch (IOException | NoSuchAlgorithmException e) {
            fail("无法创建文件: " + e.getMessage());
            return false;
        }
    }

    public boolean chunk(String transferId, int index, byte[] data) {
        if (out == null || !transferId.equals(this.transferId)) {
            lastError = "传输标识不匹配";
            return false;
        }
        if (index != nextIndex) {
            fail("块序错乱 (expected " + nextIndex + ", got " + index + ")");
            return false;
        }
        try {
            out.write(data);
            digest.update(data);
            received += data.length;
            nextIndex++;
            DownloadProgressState.progress(received, totalBytes);
            return true;
        } catch (IOException e) {
            fail("写入失败: " + e.getMessage());
            return false;
        }
    }

    public boolean end(String transferId) {
        if (out == null || !transferId.equals(this.transferId)) {
            lastError = "传输标识不匹配";
            return false;
        }
        try {
            out.close();
            out = null;
            String actual = toHex(digest.digest());
            if (received != totalBytes) {
                fail("长度校验失败 (" + received + "/" + totalBytes + ")");
                return false;
            }
            if (!expectedSha256.isEmpty() && !actual.equalsIgnoreCase(expectedSha256)) {
                fail("SHA-256 校验失败");
                return false;
            }
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            completedFile = targetFile;
            completedSha256 = actual;
            DownloadProgressState.complete();
            LOGGER.info("[charter_audio] saved {} ({} bytes, sha256={})", targetFile, received, actual);
            return true;
        } catch (IOException e) {
            fail("保存失败: " + e.getMessage());
            return false;
        }
    }

    /** 协议层失败时主动标记（清理临时文件并更新 UI 状态）。 */
    public void fail(String reason) {
        lastError = reason == null ? "" : reason;
        closeOut();
        deletePartial();
        DownloadProgressState.failed(lastError);
        LOGGER.warn("[charter_audio] transfer failed: {}", lastError);
    }

    public void reset() {
        closeOut();
        deletePartial();
        transferId = "";
        nextIndex = 0;
        received = 0;
        totalBytes = 0;
        expectedSha256 = "";
        completedFile = null;
        completedSha256 = "";
        lastError = "";
    }

    private void closeOut() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
    }

    private void deletePartial() {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isSafeName(String name) {
        return name != null && !name.isBlank() && !name.contains("..")
                && !name.contains("/") && !name.contains("\\") && !name.contains(":");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
