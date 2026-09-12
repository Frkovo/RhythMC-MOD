package cn.frkovo.rhythmcv2.rhythmcMod.client.timeline;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * ALT 调整层：透明、不暂停的 Screen，按住 ALT 时呼出原生鼠标光标，
 * 用于调整时间轴 HUD（滚轮=HUD 缩放；点击/拖动=seek；底部滑轴=平移窗口）。
 * 松开 ALT 自动关闭。
 */
public final class TimelineAdjustScreen extends Screen {

    private boolean seeking;
    private boolean draggingScroll;

    public TimelineAdjustScreen() {
        super(Text.literal("RhythMC Timeline"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        TimelineHud.clearManualCenter();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 透明：不变暗、不模糊，保持身后世界可见。
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (textRenderer == null) {
            return;
        }
        int panelWidth = Math.min(640, this.width - 40);
        int x0 = (this.width - panelWidth) / 2;
        int y0 = this.height - 96 - 26;
        context.drawText(textRenderer,
                Text.literal("滚轮: 缩放时间轴 | 点击/拖动: 跳转 | 底部滑轴: 平移窗口 | 松开 ALT 关闭"),
                this.width / 2 - 170, y0 - 26, 0xFFCCCCCC, true);
        TimelineHud.Layout layout = computeLayout();
        if (layout != null) {
            TimelineHud.drawPanel(context, layout, true, true);
        }
    }

    private TimelineHud.Layout computeLayout() {
        int panelWidth = Math.min(640, this.width - 40);
        int x0 = (this.width - panelWidth) / 2;
        int y0 = this.height - 96 - 26;
        return TimelineHud.layout(x0, y0, panelWidth, 26, true);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical > 0) {
            TimelineHud.zoomIn();
        } else if (vertical < 0) {
            TimelineHud.zoomOut();
        }
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (click.button() != 0) {
            return false;
        }
        TimelineHud.Layout layout = computeLayout();
        if (layout == null) {
            return false;
        }
        if (insideScrollbar(layout, click.x(), click.y())) {
            draggingScroll = true;
            TimelineHud.panTo(layout, click.x());
            return true;
        }
        if (insidePanel(layout, click.x(), click.y())) {
            seeking = true;
            seekTo(layout, click.x());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double offsetX, double offsetY) {
        TimelineHud.Layout layout = computeLayout();
        if (layout == null) {
            return false;
        }
        if (draggingScroll) {
            TimelineHud.panTo(layout, click.x());
            return true;
        }
        if (seeking) {
            seekTo(layout, click.x());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        seeking = false;
        draggingScroll = false;
        // 松手保留手动中心；只有 ALT 松开（removed()）才恢复自动居中。
        return false;
    }

    @Override
    public void removed() {
        TimelineHud.clearManualCenter();
        super.removed();
    }

    @Override
    public void tick() {
        if (!altHeld()) {
            close();
        }
    }

    private void seekTo(TimelineHud.Layout layout, double mouseX) {
        double ms = layout.msForX((int) Math.round(mouseX));
        TimelineHud.setManualCenter(ms);
        CharterAudioClient.get().requestViewSeek(ms);
    }

    private static boolean insidePanel(TimelineHud.Layout layout, double x, double y) {
        return x >= layout.x0() && x <= layout.x0() + layout.width()
                && y >= layout.y0() && y <= layout.y0() + layout.height();
    }

    private static boolean insideScrollbar(TimelineHud.Layout layout, double x, double y) {
        return layout.scrollHeight() > 0
                && x >= layout.scrollX() && x <= layout.scrollX() + layout.scrollWidth()
                && y >= layout.scrollY() && y <= layout.scrollY() + layout.scrollHeight();
    }

    private static boolean altHeld() {
        return cn.frkovo.rhythmcv2.rhythmcMod.client.input.ViewInput.altHeld(MinecraftClient.getInstance());
    }
}
