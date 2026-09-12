package cn.frkovo.rhythmcv2.rhythmcMod.mixin.client;

import cn.frkovo.rhythmcv2.rhythmcMod.client.audio.DownloadProgressState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.timeline.TimelineHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 音频下载进度 HUD：屏幕中下方进度条 + 文本「正在下载音乐 (x.xMB/y.yMB z%)」。
 * DONE 显示 2.5s，FAILED 显示 5s 后自动隐藏。
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void rhythmc$renderDownloadProgress(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        DownloadProgressState.Phase phase = DownloadProgressState.phase();
        if (phase == DownloadProgressState.Phase.IDLE) {
            return;
        }
        long now = System.currentTimeMillis();
        long age = now - DownloadProgressState.finishedAtMs();
        if (phase == DownloadProgressState.Phase.DONE && age > 2500) {
            return;
        }
        if (phase == DownloadProgressState.Phase.FAILED && age > 5000) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.textRenderer == null) {
            return;
        }
        long received = DownloadProgressState.received();
        long total = DownloadProgressState.totalBytes();
        String label;
        if (phase == DownloadProgressState.Phase.DOWNLOADING) {
            int percent = total > 0 ? (int) (received * 100 / total) : 0;
            label = "正在下载音乐 (" + mb(received) + "/" + mb(total) + " " + percent + "%)";
        } else if (phase == DownloadProgressState.Phase.DONE) {
            label = "音乐下载完成";
        } else {
            label = "音乐下载失败: " + DownloadProgressState.message();
        }

        Text text = Text.literal(label);
        int textWidth = client.textRenderer.getWidth(text);
        int barWidth = Math.max(180, textWidth + 24);
        int x = context.getScaledWindowWidth() / 2 - barWidth / 2;
        int y = context.getScaledWindowHeight() - 68;
        int accent = phase == DownloadProgressState.Phase.FAILED ? 0xFFFF5555 : 0xFF3FA9F5;

        context.fill(x, y, x + barWidth, y + 14, 0xAA000000);
        if (phase == DownloadProgressState.Phase.DOWNLOADING && total > 0) {
            int inner = (int) ((barWidth - 4) * Math.min(1.0, received / (double) total));
            if (inner > 0) {
                context.fill(x + 2, y + 2, x + 2 + inner, y + 12, accent);
            }
        } else if (phase == DownloadProgressState.Phase.DONE) {
            context.fill(x + 2, y + 2, x + barWidth - 2, y + 12, 0xFF55C878);
        }
        context.drawText(client.textRenderer, text, x + (barWidth - textWidth) / 2, y + 3, 0xFFFFFFFF, true);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void rhythmc$renderTimelineHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        TimelineHud.render(context);
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }
}
