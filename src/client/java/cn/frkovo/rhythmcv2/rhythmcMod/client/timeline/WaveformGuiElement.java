package cn.frkovo.rhythmcv2.rhythmcMod.client.timeline;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2fc;

/**
 * 连续 RMS 波形主体：共享边四边形带一次性提交（一个 GUI 元素），不逐列 fillRect/drawLine，
 * 无 barcode/栅栏纹理。数据不做任何平滑——只做 per-pixel 区间聚合。
 */
final class WaveformGuiElement implements SimpleGuiElementRenderState {

    private final ScreenRect bounds;
    private final ScreenRect scissor;
    private final Matrix3x2fc pose;
    /** 采样点（长度 = 列数 + 1）。 */
    private final float[] xs;
    private final float[] bodyTops;
    private final float[] bodyBottoms;
    private final int bodyColor;

    WaveformGuiElement(ScreenRect bounds, ScreenRect scissor, Matrix3x2fc pose,
                       float[] xs, float[] bodyTops, float[] bodyBottoms, int bodyColor) {
        this.bounds = bounds;
        this.scissor = scissor;
        this.pose = pose;
        this.xs = xs;
        this.bodyTops = bodyTops;
        this.bodyBottoms = bodyBottoms;
        this.bodyColor = bodyColor;
    }

    @Override
    public void setupVertices(VertexConsumer consumer) {
        for (int i = 0; i < xs.length - 1; i++) {
            consumer.vertex(pose, xs[i], bodyTops[i]).color(bodyColor);
            consumer.vertex(pose, xs[i], bodyBottoms[i]).color(bodyColor);
            consumer.vertex(pose, xs[i + 1], bodyBottoms[i + 1]).color(bodyColor);
            consumer.vertex(pose, xs[i + 1], bodyTops[i + 1]).color(bodyColor);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.empty();
    }

    @Override
    public ScreenRect scissorArea() {
        return scissor;
    }

    @Override
    public ScreenRect bounds() {
        return bounds;
    }
}
