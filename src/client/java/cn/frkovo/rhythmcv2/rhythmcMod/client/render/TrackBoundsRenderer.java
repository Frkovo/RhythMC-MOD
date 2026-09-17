package cn.frkovo.rhythmcv2.rhythmcMod.client.render;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.BoundsDataState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.timeline.TimelineGrid;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.minecraft.world.debug.gizmo.TextGizmo;

import java.util.List;

/**
 * 轨道轮廓可视化（mod 键位 `B`；只在 glob 实地播放中由插件开启）。
 *
 * <p>用 1.21.11 自带的 gizmo API（{@link GizmoDrawing}）在客户端画，服务端不下发实体：</p>
 * <ul>
 *   <li>每条 Track 的**体积盒**（像碰撞箱）：XY = 判定面正方形（默认 5×5），Z 从判定面向前
 *       （世界 −Z 方向）延伸 {@code boxLength}（默认 25）→ 12 条棱线（可穿墙）+ 6 面淡填充；</li>
 *   <li>**判定面**（盒子的 Z=0 面）：红色半透明填充 + 描边；</li>
 *   <li>**`TRACK #N` 标记**：盒子上边中点，vanilla 世界文字。</li>
 * </ul>
 *
 * <p>几何与运行时音符完全一致（复刻插件 {@code NoteObject.composeRenderState}）：
 * 本地角点 → 按 zRot→yRot→xRot 旋转（再乘 xScale/yScale/zScale）→ 加轨道位移 →
 * 相对判定面基点（{@code screenCenterLoc}，垂直 +0.5）。</p>
 */
public final class TrackBoundsRenderer {

    /** 棱线颜色（不透明红）。 */
    private static final int COLOR_EDGE = 0xFFFF5555;
    /** 判定面填充（半透明红）。 */
    private static final int COLOR_PLANE_FILL = 0x60FF0000;
    /** 线宽。 */
    private static final float LINE_WIDTH = 2.0f;
    /** 文字缩放。 */
    private static final float TEXT_SCALE = 1.0f;
    /** 标记相对盒顶再抬高（格）。 */
    private static final double LABEL_LIFT = 0.5d;

    private TrackBoundsRenderer() {
    }

    public static void register() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(TrackBoundsRenderer::onBeforeDebugRender);
    }

    private static void onBeforeDebugRender(WorldRenderContext context) {
        BoundsDataState state = CharterAudioClient.get().boundsData();
        if (!state.on()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.worldRenderer == null) {
            return;
        }
        double ms = Math.max(0d, CharterAudioClient.get().engine().positionMs());
        TimelineGrid grid = new TimelineGrid(CharterAudioClient.get().chartMeta());
        if (!grid.isUsable()) {
            return;
        }
        double beat = grid.beatAtMs(ms);
        try (GizmoDrawing.CollectorScope ignored = client.worldRenderer.startDrawingGizmos()) {
            for (BoundsDataState.Track track : state.tracks()) {
                drawTrack(state, track, beat);
            }
        }
    }

    private static void drawTrack(BoundsDataState state, BoundsDataState.Track track, double beat) {
        double xT = eval(track, 1, beat, 0d);
        double yT = eval(track, 2, beat, 0d);
        double zT = eval(track, 3, beat, 0d);
        double xS = eval(track, 4, beat, 1d);
        double yS = eval(track, 5, beat, 1d);
        double zS = eval(track, 6, beat, 1d);
        double xR = eval(track, 7, beat, 0d);
        double yR = eval(track, 8, beat, 0d);
        double zR = eval(track, 9, beat, 0d);

        double half = state.planeHalf();
        double length = state.boxLength();

        // 8 个角点：索引 bit0 = X(+/-)、bit1 = Y(+/-)、bit2 = Z(0 = 判定面 / 1 = 前方)
        // 本地 z 是「距离」：正数 = 前方（插件 composeRenderState 的 realZ = originZ - z）
        Vec3d[] corners = new Vec3d[8];
        for (int i = 0; i < 8; i++) {
            double lx = ((i & 1) == 0 ? -half : half);
            double ly = ((i & 2) == 0 ? -half : half);
            double lz = ((i & 4) == 0 ? 0d : length);
            corners[i] = toWorld(state, lx, ly, lz, xT, yT, zT, xS, yS, zS, xR, yR, zR);
        }

        // 12 条棱线（穿墙可见）
        int[][] edges = {
                {0, 1}, {2, 3}, {4, 5}, {6, 7},   // X 方向
                {0, 2}, {1, 3}, {4, 6}, {5, 7},   // Y 方向
                {0, 4}, {1, 5}, {2, 6}, {3, 7},   // Z 方向
        };
        for (int[] edge : edges) {
            GizmoDrawing.line(corners[edge[0]], corners[edge[1]], COLOR_EDGE, LINE_WIDTH).ignoreOcclusion();
        }

        // 判定面（Z = 0 面）：红色半透明填充 + 描边（唯一有颜色的面）
        GizmoDrawing.quad(corners[0], corners[2], corners[3], corners[1],
                DrawStyle.filledAndStroked(COLOR_EDGE, LINE_WIDTH, COLOR_PLANE_FILL));

        // TRACK #N：盒子上边中点（相对判定面上边再抬高一点）
        Vec3d label = toWorld(state, 0d, half + LABEL_LIFT, 0d, xT, yT, zT, xS, yS, zS, xR, yR, zR);
        GizmoDrawing.text("TRACK #" + track.trackId(), label,
                TextGizmo.Style.centered(COLOR_EDGE).scaled(TEXT_SCALE));
    }

    private static double eval(BoundsDataState.Track track, int channel, double beat, double defaultValue) {
        return BoundsDataState.evaluate(BoundsDataState.channel(track, channel), beat, defaultValue);
    }

    /**
     * 本地坐标 → 世界坐标（复刻插件 {@code NoteObject.composeRenderState} 的旋转/缩放/位移顺序，
     * 基点 = 判定面基点 + 垂直 0.5；世界 Z 取负表示向前）。
     */
    private static Vec3d toWorld(BoundsDataState state,
                                 double lx, double ly, double lz,
                                 double xT, double yT, double zT,
                                 double xS, double yS, double zS,
                                 double xR, double yR, double zR) {
        double x = lx * xS;
        double y = ly * yS;
        double z = lz * zS;

        double radX = Math.toRadians(xR);
        double radY = Math.toRadians(yR);
        double radZ = Math.toRadians(zR);
        double sinX = Math.sin(radX), cosX = Math.cos(radX);
        double sinY = Math.sin(radY), cosY = Math.cos(radY);
        double sinZ = Math.sin(radZ), cosZ = Math.cos(radZ);

        double x1 = x * cosZ - y * sinZ;
        double y1 = x * sinZ + y * cosZ;
        double z1 = z;

        double x2 = x1 * cosY + z1 * sinY;
        double y2 = y1;
        double z2 = -x1 * sinY + z1 * cosY;

        double x3 = x2;
        double y3 = y2 * cosX - z2 * sinX;
        double z3 = y2 * sinX + z2 * cosX;

        double worldX = state.baseX() + xT + x3;
        double worldY = state.baseY() + state.yOffset() + yT + y3;
        double worldZ = state.baseZ() - zT - z3;
        return new Vec3d(worldX, worldY, worldZ);
    }

    /** 便捷：Track 的通道（供外部/调试用）。 */
    public static List<BoundsDataState.Segment> channel(BoundsDataState.Track track, int index) {
        return BoundsDataState.channel(track, index);
    }
}
