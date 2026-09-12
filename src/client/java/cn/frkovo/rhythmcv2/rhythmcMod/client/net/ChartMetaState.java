package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import java.util.List;

/**
 * CHART_META(2) 缓存：BPM/offset/length + 当前 Track 分音段，供时间轴 HUD 绘制网格与音符。
 */
public final class ChartMetaState {

    public record Bpm(double beat, double bpm) {
    }

    /** 分音段（当前 Track）：从 startBeat 起，网格为 noteValue 分音符（4/8/12/16/24/32/48/64）。 */
    public record Subdivision(double startBeat, int noteValue) {
    }

    private volatile String songName = "";
    private volatile long lengthMs;
    private volatile long offsetMs;
    private volatile List<Bpm> bpms = List.of();
    private volatile List<Subdivision> subdivisions = List.of();

    public void update(String songName, long lengthMs, long offsetMs, List<Bpm> bpms,
                       List<Subdivision> subdivisions) {
        this.songName = songName == null ? "" : songName;
        this.lengthMs = Math.max(0L, lengthMs);
        this.offsetMs = offsetMs;
        this.bpms = bpms == null ? List.of() : List.copyOf(bpms);
        this.subdivisions = subdivisions == null ? List.of() : List.copyOf(subdivisions);
    }

    public void reset() {
        update("", 0L, 0L, List.of(), List.of());
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

    public List<Subdivision> subdivisions() {
        return subdivisions;
    }

    public boolean hasData() {
        return lengthMs > 0 && !bpms.isEmpty();
    }
}
