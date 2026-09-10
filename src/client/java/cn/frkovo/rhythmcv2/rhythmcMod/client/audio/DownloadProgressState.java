package cn.frkovo.rhythmcv2.rhythmcMod.client.audio;

/**
 * 下载进度共享状态（接收器写，HUD mixin 读）。全部字段 volatile，主线程访问。
 */
public final class DownloadProgressState {

    public enum Phase {
        IDLE, DOWNLOADING, DONE, FAILED
    }

    private static volatile Phase phase = Phase.IDLE;
    private static volatile String fileName = "";
    private static volatile long received;
    private static volatile long totalBytes;
    private static volatile long finishedAtMs;
    private static volatile String message = "";

    private DownloadProgressState() {
    }

    public static void start(String name, long total) {
        fileName = name == null ? "" : name;
        received = 0;
        totalBytes = Math.max(0, total);
        message = "";
        phase = Phase.DOWNLOADING;
    }

    public static void progress(long receivedBytes, long total) {
        received = receivedBytes;
        totalBytes = Math.max(0, total);
    }

    public static void complete() {
        phase = Phase.DONE;
        finishedAtMs = System.currentTimeMillis();
    }

    public static void failed(String reason) {
        message = reason == null ? "" : reason;
        phase = Phase.FAILED;
        finishedAtMs = System.currentTimeMillis();
    }

    public static void reset() {
        phase = Phase.IDLE;
        fileName = "";
        received = 0;
        totalBytes = 0;
        message = "";
    }

    public static Phase phase() {
        return phase;
    }

    public static String fileName() {
        return fileName;
    }

    public static long received() {
        return received;
    }

    public static long totalBytes() {
        return totalBytes;
    }

    public static long finishedAtMs() {
        return finishedAtMs;
    }

    public static String message() {
        return message;
    }
}
