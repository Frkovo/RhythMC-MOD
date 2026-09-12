package cn.frkovo.rhythmcv2.rhythmcMod.client.net;

/**
 * VIEW_STATE(111) 缓存：世界网格缩放级别 + 插件游标位置（时间轴 HUD 窗口中心）。
 */
public final class ViewState {

    private volatile int zoomIndex;
    private volatile double barBlocks = 32d;
    private volatile int levelCount = 1;
    private volatile double cursorMs;

    public void update(int zoomIndex, double barBlocks, int levelCount, double cursorMs) {
        this.zoomIndex = zoomIndex;
        this.barBlocks = barBlocks;
        this.levelCount = levelCount;
        this.cursorMs = cursorMs;
    }

    public void reset() {
        update(0, 32d, 1, 0d);
    }

    public int zoomIndex() {
        return zoomIndex;
    }

    public double barBlocks() {
        return barBlocks;
    }

    public int levelCount() {
        return levelCount;
    }

    public double cursorMs() {
        return cursorMs;
    }

    public boolean hasData() {
        return levelCount > 0 && barBlocks > 0;
    }
}
