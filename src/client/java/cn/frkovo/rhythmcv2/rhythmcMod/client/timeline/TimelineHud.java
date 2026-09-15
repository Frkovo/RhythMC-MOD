package cn.frkovo.rhythmcv2.rhythmcMod.client.timeline;

import cn.frkovo.rhythmcv2.rhythmcMod.client.audio.CharterAudioEngine;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.ChartMetaState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.ChartNotesState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.EasingCurve;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.EventHudState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.ViewState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.text.Text;
import org.joml.Matrix3x2f;

import java.util.List;

/**
 * 底部时间轴 HUD（MajdataEdit 风格）：绿色波形 + 黄色小节/拍线 + 紫色 BPM 切换 +
 * 按类型分行的音符标记 + 红色播放头 + A-B 循环区间 + 底部滑轴。
 *
 * <p>窗口视图：默认显示 64 拍（16 小节），HUD 独立缩放 {0.5×, 1×, 2×, 4×}（ALT 调整层内滚轮），
 * 与世界网格缩放（SHIFT+滚轮）互不影响。窗口中心：播放中=音频位置，编辑中=插件游标，
 * 手动平移时=手动中心。</p>
 */
public final class TimelineHud {

    public static final double[] ZOOM_FACTORS = {0.5d, 1d, 2d, 4d, 8d};
    private static final double BASE_VISIBLE_BEATS = 64d;

    private static final int HEIGHT = 26;
    private static final int BOTTOM_OFFSET = 96;
    private static final int MAX_WIDTH = 640;
    private static final int MARGIN_X = 20;
    private static final int MAX_MARKERS = 8000;
    private static final int SCROLLBAR_HEIGHT = 6;

    private static volatile boolean visible = true;
    private static volatile int zoomIndex = 1;
    /** 手动平移中心（ms）；NaN = 自动跟随播放头/游标。 */
    private static volatile double manualCenterMs = Double.NaN;

    /** 波形显示层 scratch（复用，避免每帧分配）。 */
    private static float[] waveXs = new float[0];
    private static float[] waveTops = new float[0];
    private static float[] waveBottoms = new float[0];

    private TimelineHud() {
    }

    public static boolean toggle() {
        visible = !visible;
        return visible;
    }

    public static boolean visible() {
        return visible;
    }

    public static void zoomIn() {
        zoomIndex = Math.min(ZOOM_FACTORS.length - 1, zoomIndex + 1);
    }

    public static void zoomOut() {
        zoomIndex = Math.max(0, zoomIndex - 1);
    }

    public static double zoomFactor() {
        return ZOOM_FACTORS[zoomIndex];
    }

    public static int zoomIndex() {
        return zoomIndex;
    }

    public static void setManualCenter(double ms) {
        manualCenterMs = Math.max(0d, ms);
    }

    public static void clearManualCenter() {
        manualCenterMs = Double.NaN;
    }

    private static long centerMs(CharterAudioEngine engine, ViewState view) {
        if (!Double.isNaN(manualCenterMs)) {
            return (long) manualCenterMs;
        }
        if (engine.isPlaying()) {
            return engine.positionMs();
        }
        if (view.hasData()) {
            return (long) Math.max(0d, view.cursorMs());
        }
        return engine.positionMs();
    }

    /** 时间轴几何与映射（普通 HUD 与 ALT 调整层共用；命中测试也用它）。 */
    public record Layout(int x0, int y0, int width, int height,
                         long lengthMs, TimelineGrid grid, int lastBeat,
                         double msFrom, double msTo, long centerMs,
                         int scrollX, int scrollY, int scrollWidth, int scrollHeight,
                         double scrollFrom, double scrollTo) {

        public int xForMs(double ms) {
            return x0 + (int) Math.round((ms - msFrom) / Math.max(1.0d, msTo - msFrom) * width);
        }

        public double msForX(int x) {
            double frac = Math.max(0d, Math.min(1d, (x - x0) / (double) width));
            return msFrom + frac * (msTo - msFrom);
        }
    }

    public static Layout layout(int x0, int y0, int width, int height, boolean withScrollbar) {
        CharterAudioClient audio = CharterAudioClient.get();
        ChartMetaState meta = audio.chartMeta();
        CharterAudioEngine engine = audio.engine();
        long lengthMs = meta.hasData() ? meta.lengthMs() : engine.lengthMs();
        if (lengthMs <= 0) {
            return null;
        }
        TimelineGrid grid = meta.hasData() ? new TimelineGrid(meta) : null;
        long center = centerMs(engine, audio.viewState());

        double beatFrom;
        double beatTo;
        double msFrom;
        double msTo;
        int lastBeat = 0;
        if (grid != null && grid.isUsable()) {
            lastBeat = Math.max(1, grid.lastBeat());
            double visibleBeats = BASE_VISIBLE_BEATS / zoomFactor();
            double centerBeat = grid.beatAtMs(center);
            beatFrom = centerBeat - visibleBeats / 2d;
            beatTo = centerBeat + visibleBeats / 2d;
            if (beatFrom < 0d) {
                double shift = -beatFrom;
                beatFrom += shift;
                beatTo += shift;
            }
            if (beatTo > lastBeat) {
                double shift = beatTo - lastBeat;
                beatFrom -= shift;
                beatTo -= shift;
            }
            beatFrom = Math.max(0d, beatFrom);
            beatTo = Math.max(beatFrom + 1d, beatTo);
            msFrom = grid.msAtBeat(beatFrom);
            msTo = grid.msAtBeat(beatTo);
        } else {
            double half = lengthMs / (2d * zoomFactor());
            msFrom = Math.max(0d, center - half);
            msTo = Math.min(lengthMs, center + half);
            beatFrom = 0d;
            beatTo = 0d;
        }
        if (msTo <= msFrom) {
            msTo = msFrom + 1000d;
        }
        msFrom = Math.max(0d, msFrom);
        msTo = Math.min(lengthMs, msTo);
        if (msTo <= msFrom) {
            msTo = Math.min(lengthMs, msFrom + 1000d);
        }
        double scrollFrom = grid != null && grid.isUsable() ? clamp01(beatFrom / lastBeat) : clamp01(msFrom / lengthMs);
        double scrollTo = grid != null && grid.isUsable() ? clamp01(beatTo / lastBeat) : clamp01(msTo / lengthMs);
        int scrollY = y0 + height + 14;
        int scrollHeight = withScrollbar ? SCROLLBAR_HEIGHT : 0;
        return new Layout(x0, y0, width, height, lengthMs, grid, lastBeat,
                msFrom, msTo, center,
                x0, scrollY, width, scrollHeight, scrollFrom, scrollTo);
    }

    /** 普通 HUD 渲染入口（InGameHud mixin 调用）。 */
    public static void render(DrawContext context) {
        if (!visible) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || client.player == null) {
            return;
        }
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int width = Math.min(MAX_WIDTH, screenWidth - MARGIN_X * 2);
        if (width < 80) {
            return;
        }
        int x0 = (screenWidth - width) / 2;
        int y1 = screenHeight - BOTTOM_OFFSET;
        int y0 = y1 - HEIGHT;
        Layout layout = layout(x0, y0, width, HEIGHT, false);
        if (layout == null) {
            return;
        }
        drawPanel(context, layout, true, false);
    }

    /**
     * per-pixel 区间聚合结果 → 连续 RMS 四边形带（无描边）。
     * 不做任何显示平滑：保留真实瞬态与段内动态。
     */
    private static void buildWaveformElement(DrawContext context, Layout layout,
                                             CharterAudioEngine.WaveformWindow wave) {
        float[] rms = wave.rms();
        int columns = rms.length;
        if (columns < 2) {
            return;
        }
        int points = columns + 1;
        if (waveXs.length < points) {
            waveXs = new float[points];
            waveTops = new float[points];
            waveBottoms = new float[points];
        }
        int mid = layout.y0() + layout.height() / 2;
        int maxHalf = Math.max(2, layout.height() / 2 - 1);
        for (int i = 0; i < points; i++) {
            int c = Math.min(columns - 1, i);
            int bodyHalf = Math.max(1, Math.round(clamp01(rms[c]) * maxHalf));
            waveXs[i] = layout.x0() + i;
            waveTops[i] = mid - bodyHalf;
            waveBottoms[i] = mid + bodyHalf;
        }
        ScreenRect bounds = new ScreenRect(layout.x0(), layout.y0(), layout.width(), layout.height());
        context.state.addSimpleElement(new WaveformGuiElement(
                bounds, context.scissorStack.peekLast(),
                new Matrix3x2f(context.getMatrices()),
                waveXs, waveTops, waveBottoms, 0x8022AA44));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    // ------------------------------------------------------------------
    // 事件曲线（EVENT_HUD 117）
    // ------------------------------------------------------------------

    /** 通道曲线配色（与插件 EventRenderer.channelColor 一致）。 */
    public static int channelColor(int channel) {
        return switch (Math.floorMod(channel, 10)) {
            case 0 -> 0xFF00DCC8;
            case 1 -> 0xFFFF6E6E;
            case 2 -> 0xFF78FF8C;
            case 3 -> 0xFF6EA0FF;
            case 4 -> 0xFFFFC850;
            case 5 -> 0xFFE6E650;
            case 6 -> 0xFFFF963C;
            case 7 -> 0xFFDC82FF;
            case 8 -> 0xFFB46EFF;
            case 9 -> 0xFFFF78DC;
            default -> 0xFFFFFFFF;
        };
    }

    /** 通道中性值（与插件 EventRenderer.midValue 一致）：流速/缩放 1.0（常态），其余 0。 */
    private static double neutralValue(int channel) {
        return switch (Math.floorMod(channel, 10)) {
            case 0, 4, 5, 6 -> 1d;
            default -> 0d;
        };
    }

    /** 通道名（与插件 EventChannel.label 一致）。 */
    private static String channelLabel(int channel) {
        return switch (Math.floorMod(channel, 10)) {
            case 0 -> "流速";
            case 1 -> "X 位移";
            case 2 -> "Y 位移";
            case 3 -> "Z 位移";
            case 4 -> "X 缩放";
            case 5 -> "Y 缩放";
            case 6 -> "Z 缩放";
            case 7 -> "X 旋转";
            case 8 -> "Y 旋转";
            default -> "Z 旋转";
        };
    }

    /** 通道单位后缀（与插件一致：流速是倍率）。 */
    private static String channelUnit(int channel) {
        return switch (Math.floorMod(channel, 10)) {
            case 0 -> "×";
            case 4, 5, 6 -> "倍";
            case 7, 8, 9 -> "度";
            default -> "格";
        };
    }

    /**
     * 窗口内自适应归一化（展示趋势）：返回 {@code {min, max, flatFlag}}，
     * 取值范围 = 窗口采样值 ∪ 通道中性值，再留 10% 边距。
     */
    /**
     * 纵向归一化（**绝对**：按整条通道的所有段，不随 HUD 窗口/播放头变化）：
     * 返回 {@code {min, max, flatFlag}}，取值范围 = 全通道采样值 ∪ 通道中性值，再留 10% 边距。
     */
    private static double[] fitScale(EventHudState.Snapshot hud) {
        double neutral = neutralValue(hud.channel());
        double min = neutral;
        double max = neutral;
        for (EventHudState.Segment segment : hud.segments()) {
            for (int i = 0; i <= 33; i++) {
                double beat = segment.startBeat() + (segment.endBeat() - segment.startBeat()) * i / 33d;
                double value = segmentValue(segment, beat);
                if (!Double.isFinite(value)) {
                    continue;
                }
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        double range = max - min;
        if (range < 1.0E-9d) {
            return new double[]{min, max, 1d};
        }
        double pad = range * 0.1d;
        return new double[]{min - pad, max + pad, 0d};
    }

    /** 某拍处的通道取值（按段取值 + 缓动；跳变从该拍起生效）。 */
    private static double valueAtBeat(EventHudState.Snapshot hud, double beat) {
        EventHudState.Segment previous = null;
        for (EventHudState.Segment segment : hud.segments()) {
            if (segment.jump()) {
                if (segment.startBeat() <= beat + 1.0E-6d) {
                    previous = segment;
                }
                continue;
            }
            if (beat <= segment.endBeat() + 1.0E-6d && beat >= segment.startBeat() - 1.0E-6d) {
                return segmentValue(segment, beat);
            }
            if (beat > segment.endBeat()) {
                previous = segment;
            }
        }
        return previous == null ? neutralValue(hud.channel()) : previous.endValue();
    }

    /** 数值 → 曲线带内 y 像素（按窗口归一化映射，超出钳到边缘）。 */
    private static int yFor(double value, double[] fit, int top, int bottom) {
        double center = (top + bottom) / 2d;
        double half = Math.max(1d, (bottom - top) / 2d);
        if (fit[2] > 0.5d) {
            return (int) Math.round(center);
        }
        double t = (value - fit[0]) / Math.max(1.0E-9d, fit[1] - fit[0]) * 2d - 1d;
        t = Math.max(-1d, Math.min(1d, t));
        return (int) Math.round(center - t * half);
    }

    /** 段内取值（缓动与插件同表）。 */
    private static double segmentValue(EventHudState.Segment segment, double beat) {
        if (segment.jump()) {
            return segment.endValue();
        }
        double t = (beat - segment.startBeat()) / Math.max(1.0E-6d, segment.endBeat() - segment.startBeat());
        return EasingCurve.ease(segment.startValue(), segment.endValue(),
                Math.max(0d, Math.min(1d, t)), segment.easing());
    }

    private static String shortValue(double value) {
        double abs = Math.abs(value);
        if (abs >= 1000d) {
            return String.format(java.util.Locale.ROOT, "%.0f", value);
        }
        if (abs >= 10d) {
            return String.format(java.util.Locale.ROOT, "%.2f", value);
        }
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** 在时间轴上叠加当前通道的事件曲线（折线 + 端点方块 + 跳变竖线 + 中性参考线 + 通道标签）。 */
    public static void drawEventCurve(DrawContext context, Layout layout) {
        EventHudState.Snapshot hud = CharterAudioClient.get().eventHud().snapshot();
        if (hud.trackId() < 0 || hud.segments().isEmpty()) {
            return;
        }
        TimelineGrid grid = layout.grid();
        if (grid == null || !grid.isUsable()) {
            return;
        }
        int x0 = layout.x0();
        int x1 = x0 + layout.width();
        int top = layout.y0() + 2;
        int bottom = layout.y0() + Math.max(6, layout.height() - 7);
        int color = channelColor(hud.channel());
        int channel = hud.channel();
        double[] fit = fitScale(hud);

        // 中性参考虚线（同一归一化映射）
        double neutral = neutralValue(channel);
        int neutralY = yFor(neutral, fit, top, bottom);
        for (int x = x0; x <= x1; x += 6) {
            context.fill(x, neutralY, x + 2, neutralY + 1, 0x60AAAAAA);
        }

        int previousX = Integer.MIN_VALUE;
        int previousY = 0;
        java.util.List<EventHudState.Segment> segments = hud.segments();
        for (int i = 0; i < segments.size(); i++) {
            EventHudState.Segment segment = segments.get(i);
            double msA = grid.msAtBeat(Math.max(0d, segment.startBeat()));
            double msB = grid.msAtBeat(Math.max(0d, segment.endBeat()));
            if (msB < layout.msFrom() || msA > layout.msTo()) {
                continue;
            }
            int fromX = Math.max(x0, layout.xForMs(msA));
            int toX = Math.min(x1, layout.xForMs(Math.max(msA, msB)));
            if (toX <= fromX) {
                toX = Math.min(x1, fromX + 1);
            }
            previousX = Integer.MIN_VALUE;
            for (int x = fromX; x <= toX; x++) {
                double beat = grid.beatAtMs(layout.msForX(x));
                int y = yFor(valueAtBeat(hud, beat), fit, top, bottom);
                if (previousX != Integer.MIN_VALUE) {
                    int lo = Math.min(previousY, y);
                    int hi = Math.max(previousY, y);
                    context.fill(x, lo, x + 1, hi + 1, color);
                } else {
                    context.fill(x, y, x + 1, y + 1, color);
                }
                previousX = x;
                previousY = y;
            }
            // 边界不连续（跳变）：在公共边界拍画一条竖线
            if (i + 1 < segments.size()
                    && Math.abs(segment.endValue() - segments.get(i + 1).startValue()) > 1.0E-6d) {
                int x = Math.max(x0, Math.min(x1 - 1, layout.xForMs(msB)));
                int yA = yFor(segment.endValue(), fit, top, bottom);
                int yB = yFor(segments.get(i + 1).startValue(), fit, top, bottom);
                context.fill(x, Math.min(yA, yB), x + 1, Math.max(yA, yB) + 1, color);
            }
            endpointSquare(context, layout, x0, x1, fit, color, segment.startBeat(), segment.startValue());
            if (!segment.jump()) {
                endpointSquare(context, layout, x0, x1, fit, color, segment.endBeat(), segment.endValue());
            }
        }

        // 通道标签 + 本次显示范围（让归一化透明）
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.textRenderer != null) {
            String unit = channelUnit(channel);
            String label = channelLabel(channel) + "（" + unit + "） · 显示 "
                    + shortValue(fit[0]) + "–" + shortValue(fit[1]) + unit;
            context.drawText(client.textRenderer, Text.literal(label), x0 + 2, top, color, true);
        }
    }

    private static void endpointSquare(DrawContext context, Layout layout, int x0, int x1, double[] fit,
                                       int color, double beat, double value) {
        TimelineGrid grid = layout.grid();
        if (grid == null) {
            return;
        }
        int x = layout.xForMs(grid.msAtBeat(Math.max(0d, beat)));
        if (x < x0 || x > x1) {
            return;
        }
        int top = layout.y0() + 2;
        int bottom = layout.y0() + Math.max(6, layout.height() - 7);
        int y = yFor(value, fit, top, bottom);
        context.fill(x - 1, y - 1, x + 2, y + 2,
                (color & 0x00FFFFFF) | 0xC0000000);
    }

    /** 鼠标是否点在某个段端点上（返回段号，-1 = 未命中）。 */
    public static int eventEndpointAt(Layout layout, double mouseX, double mouseY) {
        EventHudState.Snapshot hud = CharterAudioClient.get().eventHud().snapshot();
        if (hud.trackId() < 0 || hud.segments().isEmpty()) {
            return -1;
        }
        TimelineGrid grid = layout.grid();
        if (grid == null || !grid.isUsable()) {
            return -1;
        }
        if (mouseY < layout.y0() || mouseY > layout.y0() + layout.height()) {
            return -1;
        }
        for (int i = 0; i < hud.segments().size(); i++) {
            EventHudState.Segment segment = hud.segments().get(i);
            for (double beat : new double[]{segment.startBeat(), segment.endBeat()}) {
                int x = layout.xForMs(grid.msAtBeat(Math.max(0d, beat)));
                if (Math.abs(mouseX - x) <= 3d) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 绘制时间轴面板（普通 HUD 与 ALT 调整层共用）。 */
    public static void drawPanel(DrawContext context, Layout layout, boolean showZoomLabel, boolean highlightHover) {
        CharterAudioEngine engine = CharterAudioClient.get().engine();
        int x0 = layout.x0();
        int y0 = layout.y0();
        int x1 = x0 + layout.width();
        int y1 = y0 + layout.height();

        context.fill(x0, y0, x1, y1, 0xCC141414);
        context.fill(x0, y0, x1, y0 + 1, 0x55FFFFFF);
        context.fill(x0, y1 - 1, x1, y1, 0x55FFFFFF);

        // 连续 RMS 能量带（pyramid 聚合；一次提交的多边形，无描边、无逐列竖线）
        CharterAudioEngine.WaveformWindow wave = engine.waveformWindow(
                layout.width(), Math.round(layout.msFrom()), Math.round(layout.msTo()));
        if (!wave.isEmpty()) {
            buildWaveformElement(context, layout, wave);
        }

        TimelineGrid grid = layout.grid();
        ChartMetaState meta = CharterAudioClient.get().chartMeta();
        if (grid != null && grid.isUsable()) {
            double beatFrom = grid.beatAtMs(layout.msFrom());
            double beatTo = grid.beatAtMs(layout.msTo());
            double beatsPerPx = (beatTo - beatFrom) / Math.max(1, layout.width());
            int firstBeat = (int) Math.ceil(beatFrom - 1.0E-6d);
            int lastBeat = (int) Math.floor(beatTo + 1.0E-6d);
            // 分音线（相位 A，与地尺同密度；间距 < 2.5px 自动抽稀）
            List<Double> subBeats = new java.util.ArrayList<>();
            grid.appendSubdivisionBeats(beatFrom, beatTo, subBeats, beatsPerPx * 2.5d);
            for (double subBeat : subBeats) {
                if (Math.abs(subBeat - Math.rint(subBeat)) < 1.0E-6d) {
                    continue; // 整拍线由下面统一画
                }
                int x = layout.xForMs(grid.msAtBeat(subBeat));
                context.fill(x, y0 + 1, x + 1, y1 - 1, 0x60FF9AC9);
            }
            // 拍线 / 小节线（颜色对齐地尺：小节=红、拍=洋红）
            boolean drawBeats = beatsPerPx <= 1d / 3d;
            for (int beat = Math.max(0, firstBeat); beat <= lastBeat; beat++) {
                double ms = grid.msAtBeat(beat);
                if (ms < layout.msFrom() || ms > layout.msTo()) {
                    continue;
                }
                int x = layout.xForMs(ms);
                if (beat % 4 == 0) {
                    context.fill(x, y0 + 1, x + 2, y1 - 1, 0xC0FF5555);
                } else if (drawBeats) {
                    context.fill(x, y0 + 1, x + 1, y1 - 1, 0x70FF55FF);
                }
            }
            // 底部小节刻度
            for (int beat = Math.max(0, firstBeat / 4 * 4); beat <= lastBeat; beat += 4) {
                double ms = grid.msAtBeat(beat);
                if (ms < layout.msFrom() || ms > layout.msTo()) {
                    continue;
                }
                int x = layout.xForMs(ms);
                context.fill(x, y1 - 5, x + 1, y1 - 1, 0xA0FFFFFF);
            }
            // BPM 切换线（紫）
            if (meta.hasData()) {
                for (ChartMetaState.Bpm bpm : meta.bpms()) {
                    double ms = grid.msAtBeat(bpm.beat());
                    if (ms < layout.msFrom() || ms > layout.msTo()) {
                        continue;
                    }
                    int x = layout.xForMs(ms);
                    context.fill(x - 1, y0, x + 1, y1, 0xE0B36BFF);
                }
            }
            // ===== 标签行：上标=功能（1/N、BPM=x），下标=BEAT 号 =====
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.textRenderer != null) {
                int topY = y0 - 10;
                int lastTopX = Integer.MIN_VALUE;
                // 左边缘：窗口内的当前分音（保证始终可见）
                int leftNoteValue = noteValueAt(meta, beatFrom);
                if (leftNoteValue > 0) {
                    String text = "1/" + leftNoteValue;
                    context.drawText(client.textRenderer, Text.literal(text), x0 + 2, topY, 0xFFFF9AC9, false);
                    lastTopX = x0 + 2 + client.textRenderer.getWidth(text);
                }
                // 分音段起点
                for (ChartMetaState.Subdivision sub : meta.subdivisions()) {
                    if (sub.startBeat() <= beatFrom || sub.startBeat() > beatTo) {
                        continue;
                    }
                    int x = layout.xForMs(grid.msAtBeat(sub.startBeat()));
                    if (x < lastTopX + 8) {
                        continue;
                    }
                    String text = "1/" + sub.noteValue();
                    context.drawText(client.textRenderer, Text.literal(text), x + 2, topY, 0xFFFF9AC9, false);
                    lastTopX = x + 2 + client.textRenderer.getWidth(text);
                }
                // BPM 切换
                if (meta.hasData()) {
                    for (ChartMetaState.Bpm bpm : meta.bpms()) {
                        double ms = grid.msAtBeat(bpm.beat());
                        if (ms < layout.msFrom() || ms > layout.msTo()) {
                            continue;
                        }
                        int x = layout.xForMs(ms);
                        if (x < lastTopX + 8) {
                            continue;
                        }
                        String text = "BPM=" + formatNumber(bpm.bpm());
                        context.drawText(client.textRenderer, Text.literal(text), x + 2, topY, 0xFFB36BFF, false);
                        lastTopX = x + 2 + client.textRenderer.getWidth(text);
                    }
                }
                // 下标：BEAT 号（小节线 + BPM 切换点；重叠跳过）
                List<Double> numberBeats = new java.util.ArrayList<>();
                for (int beat = Math.max(0, firstBeat / 4 * 4); beat <= lastBeat; beat += 4) {
                    numberBeats.add((double) beat);
                }
                if (meta.hasData()) {
                    for (ChartMetaState.Bpm bpm : meta.bpms()) {
                        numberBeats.add(bpm.beat());
                    }
                }
                numberBeats.sort(Double::compare);
                int lastBottomX = Integer.MIN_VALUE;
                for (double numberBeat : numberBeats) {
                    if (numberBeat < beatFrom || numberBeat > beatTo) {
                        continue;
                    }
                    int x = layout.xForMs(grid.msAtBeat(numberBeat));
                    String text = formatNumber(numberBeat);
                    int width = client.textRenderer.getWidth(text);
                    if (x < lastBottomX + 8) {
                        continue;
                    }
                    context.drawText(client.textRenderer, Text.literal(text), x + 2, y1 + 2, 0xFFFF5555, false);
                    lastBottomX = x + 2 + width;
                }
            }
            // 音符标记
            List<ChartNotesState.NoteMarker> markers = CharterAudioClient.get().chartNotes().notes();
            if (!markers.isEmpty()) {
                int from = lowerBound(markers, beatFrom);
                int stride = Math.max(1, markers.size() / MAX_MARKERS);
                for (int i = from; i < markers.size(); i += stride) {
                    ChartNotesState.NoteMarker marker = markers.get(i);
                    if (marker.beat() > beatTo) {
                        break;
                    }
                    if (marker.beat() < beatFrom) {
                        continue;
                    }
                    int x = layout.xForMs(grid.msAtBeat(marker.beat()));
                    int row = y0 + 3 + Math.min(3, Math.max(0, marker.type())) * 4;
                    context.fill(x - 1, row, x + 2, row + 3, colorFor(marker.type()));
                }
            }
        }

        // 玩家/游标位置（黄色，区别于红色音频播放头）
        ViewState view = CharterAudioClient.get().viewState();
        if (view.hasData()) {
            double cursorMs = view.cursorMs();
            if (cursorMs >= layout.msFrom() && cursorMs <= layout.msTo()) {
                int cx = layout.xForMs(cursorMs);
                context.fill(cx - 1, y0, cx + 1, y1, 0xFFFFE066);
                context.fill(cx - 3, y0, cx + 3, y0 + 3, 0xFFFFE066);
            }
        }

        // A-B 循环
        long loopA = engine.loopAMs();
        long loopB = engine.loopBMs();
        if (loopA >= 0 && loopB > loopA) {
            int xa = layout.xForMs(Math.max(layout.msFrom(), Math.min(layout.msTo(), loopA)));
            int xb = layout.xForMs(Math.max(layout.msFrom(), Math.min(layout.msTo(), loopB)));
            if (xb > xa) {
                context.fill(xa, y0 + 1, xb, y1 - 1, 0x30FFE066);
                context.fill(xa, y0 + 1, xa + 1, y1 - 1, 0xC0FFE066);
                context.fill(xb - 1, y0 + 1, xb, y1 - 1, 0xC0FFE066);
            }
        }

        // 播放头
        double position = engine.isLoaded() ? engine.positionMs() : 0d;
        if (position >= layout.msFrom() && position <= layout.msTo()) {
            int px = layout.xForMs(position);
            context.fill(px - 1, y0, px + 1, y1, 0xFFFF4040);
            context.fill(px - 3, y0 - 4, px + 3, y0, 0xFFFF4040);
        }

        // 当前通道的事件曲线（EVENT_HUD 117）：折线 + 端点方块 + 跳变竖线
        drawEventCurve(context, layout);

        // 底部滑轴（整曲缩略 + 窗口框）
        if (layout.scrollHeight() > 0) {
            int sy = layout.scrollY();
            int sh = layout.scrollHeight();
            context.fill(layout.scrollX(), sy, layout.scrollX() + layout.scrollWidth(), sy + sh, 0x88000000);
            int boxX0 = layout.scrollX() + (int) Math.round(layout.scrollFrom() * layout.scrollWidth());
            int boxX1 = layout.scrollX() + (int) Math.round(layout.scrollTo() * layout.scrollWidth());
            if (boxX1 - boxX0 < 8) {
                boxX1 = boxX0 + 8;
            }
            context.fill(boxX0, sy, boxX1, sy + sh, 0xA0FFD23F);
        }

        // 缩放标签
        if (showZoomLabel) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.textRenderer != null) {
                String label = String.format(java.util.Locale.ROOT, "×%.1f", zoomFactor());
                Text text = Text.literal(label);
                int tw = client.textRenderer.getWidth(text);
                context.drawText(client.textRenderer, text, x1 - tw - 4, y0 + 3, 0xCCFFFFFF, true);
            }
        }
    }

    /** 滑轴拖拽：把鼠标 x 位置转成手动窗口中心。 */
    public static void panTo(Layout layout, double mouseX) {
        double frac = clamp01((mouseX - layout.scrollX()) / (double) Math.max(1, layout.scrollWidth()));
        double ms;
        if (layout.grid() != null && layout.grid().isUsable() && layout.lastBeat() > 0) {
            ms = layout.grid().msAtBeat(frac * layout.lastBeat());
        } else {
            ms = frac * layout.lengthMs();
        }
        setManualCenter(ms);
    }

    private static int lowerBound(List<ChartNotesState.NoteMarker> markers, double beat) {
        int lo = 0;
        int hi = markers.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (markers.get(mid).beat() < beat) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    /** 窗口左侧所在分音段（无则 0）。 */
    private static int noteValueAt(ChartMetaState meta, double beat) {
        int noteValue = 0;
        for (ChartMetaState.Subdivision sub : meta.subdivisions()) {
            if (sub.startBeat() <= beat + 1.0E-9d) {
                noteValue = sub.noteValue();
            } else {
                break;
            }
        }
        return noteValue;
    }

    /** 整数直接输出；非整数保留两位小数。 */
    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9d) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static int colorFor(int type) {
        return switch (type) {
            case 1 -> 0xFFFFE066; // LOOK
            case 2 -> 0xFF66E0FF; // HOLD
            case 3 -> 0xFFFF6060; // DODGE
            default -> 0xFFFFFFFF; // TAP
        };
    }
}
