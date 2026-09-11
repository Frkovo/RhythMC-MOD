package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import java.util.List;

/**
 * CHART_META(2) 缓存：BPM/offset/length，供时间轴 HUD 绘制节拍网格。
 */
public final class ChartMetaState {

    public record Bpm(double beat, double bpm) {
    }

    private volatile String songName = "";
    private volatile long lengthMs;
    private volatile long offsetMs;
    private volatile List<Bpm> bpms = List.of();

    public void update(String songName, long lengthMs, long offsetMs, List<Bpm> bpms) {
        this.songName = songName == null ? "" : songName;
        this.lengthMs = Math.max(0L, lengthMs);
        this.offsetMs = offsetMs;
        this.bpms = bpms == null ? List.of() : List.copyOf(bpms);
    }

    public void reset() {
        update("", 0L, 0L, List.of());
    }

    public String songName() {
        return songName;
    }

    public long lengthMs() {
        return lengthMs;
    }

    public long offsetMs() {
        return offsetMs;
    }

    public List<Bpm> bpms() {
        return bpms;
    }

    public boolean hasData() {
        return lengthMs > 0 && !bpms.isEmpty();
    }
}
