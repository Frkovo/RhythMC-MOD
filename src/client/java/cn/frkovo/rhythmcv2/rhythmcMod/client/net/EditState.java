package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

/**
 * EDIT_STATE(114) 缓存：选中音符的完整属性快照 + 编辑边界 + undo/redo 可用性。
 * {@code ok=false} 表示无选中/已关闭（属性面板应关闭）。
 */
public final class EditState {

    /** 音符类型序号（与插件 NoteType.ordinal 一致）：0 TAP / 1 LOOK / 2 HOLD / 3 DODGE。 */
    public static final int TYPE_TAP = 0;
    public static final int TYPE_LOOK = 1;
    public static final int TYPE_HOLD = 2;
    public static final int TYPE_DODGE = 3;

    /** HOLD 链手动边界（与插件 HoldBoundary.ordinal 一致）：0 自动 / 1 链首 / 2 链尾。 */
    public static final int BOUNDARY_NONE = 0;
    public static final int BOUNDARY_START = 1;
    public static final int BOUNDARY_END = 2;

    public record Snapshot(boolean ok, String reason, int type, double beat,
                           double posX, double posY, double posZ,
                           float scaleX, float scaleY, float scaleZ,
                           float rotX, float rotY, float rotZ,
                           int holdGroup, int holdGroupSize, int holdGroupIndex, int holdBoundary,
                           int holdGroupManual,
                           double maxHalfWidth, double maxHalfHeight, double beatStep,
                           boolean canUndo, boolean canRedo) {
    }

    private volatile Snapshot snapshot = closed();

    public static Snapshot closed() {
        return new Snapshot(false, "", TYPE_TAP, 0d,
                0d, 0d, 0d, 1f, 1f, 1f, 0f, 0f, 0f, -1, 1, 0, BOUNDARY_NONE, -1,
                2.5d, 3.0d, 0.25d, false, false);
    }

    public void update(Snapshot snapshot) {
        this.snapshot = snapshot == null ? closed() : snapshot;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean open() {
        return snapshot.ok();
    }

    public void reset() {
        snapshot = closed();
    }
}
