package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

import java.util.List;

/**
 * CHART_NOTES(109) 缓存：时间轴 HUD 的音符标记（beat + NoteType ordinal），全量快照。
 */
public final class ChartNotesState {

    public record NoteMarker(double beat, int type) {
    }

    /** 与插件上限一致（远低于客户端 1MiB 消息上限）。 */
    public static final int MAX_NOTES = 65536;

    private volatile List<NoteMarker> notes = List.of();

    public void update(List<NoteMarker> markers) {
        this.notes = markers == null ? List.of() : List.copyOf(markers);
    }

    public void reset() {
        notes = List.of();
    }

    public List<NoteMarker> notes() {
        return notes;
    }

    public boolean hasData() {
        return !notes.isEmpty();
    }
}
