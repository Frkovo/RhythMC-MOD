package cn.frkovo.rhythmcv2.rhythmcMod.client.hud;

import cn.frkovo.rhythmcv2.rhythmcMod.client.input.CharterKeybinds;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 右下角「当前可用按键提示」：仅在编辑器会话（已收到 CHART_META）且未打开任何界面时显示。
 * 打开音符/事件面板或 ALT 调整层时自动隐藏（那些界面底部已有各自的按键提示）。
 *
 * <p>固定组合键（中键 / ALT / Shift+滚轮 / Ctrl+Z·Y）始终列出；
 * 可配置键位来自 {@link CharterKeybinds#activeHints()}，未绑定的不显示。
 * 位置贴着右下角、悬在原版快捷栏上方，避免遮挡血量/饥饿条。</p>
 */
public final class KeyHintHud {

    private static final int MARGIN_X = 6;
    /** 原版快捷栏高约 22px，留出间距悬在其上方。 */
    private static final int MARGIN_BOTTOM = 24;
    private static final int PADDING = 4;
    private static final int LINE_H = 10;
    private static final int KEY_GAP = 6;

    private static final int BG_COLOR = 0xAA000000;
    private static final int KEY_COLOR = 0xFFFFD23F;
    private static final int ACTION_COLOR = 0xFFDDDDDD;

    private static final List<String[]> FIXED = List.of(
            new String[]{"中键", "选中音符"},
            new String[]{"ALT", "时间轴调整"},
            new String[]{"Shift+滚轮", "网格缩放"},
            new String[]{"Ctrl+Z/Y", "撤销/重做"});

    private static volatile boolean visible = true;

    private KeyHintHud() {
    }

    public static void toggle() {
        visible = !visible;
    }

    public static boolean visible() {
        return visible;
    }

    public static void render(DrawContext context) {
        if (!visible) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || client.player == null
                || client.currentScreen != null) {
            return;
        }
        if (!CharterAudioClient.get().chartMeta().hasData()) {
            return;
        }

        List<String[]> lines = new ArrayList<>(FIXED);
        for (CharterKeybinds.Hint hint : CharterKeybinds.activeHints()) {
            lines.add(new String[]{hint.binding().getBoundKeyLocalizedText().getString(), hint.action()});
        }

        int keyWidth = 0;
        int actionWidth = 0;
        for (String[] line : lines) {
            keyWidth = Math.max(keyWidth, client.textRenderer.getWidth(line[0]));
            actionWidth = Math.max(actionWidth, client.textRenderer.getWidth(line[1]));
        }
        int blockWidth = PADDING * 2 + keyWidth + KEY_GAP + actionWidth;
        int blockHeight = PADDING * 2 + lines.size() * LINE_H;
        int x = context.getScaledWindowWidth() - MARGIN_X - blockWidth;
        int y = context.getScaledWindowHeight() - MARGIN_BOTTOM - blockHeight;
        context.fill(x, y, x + blockWidth, y + blockHeight, BG_COLOR);

        int keyX = x + PADDING;
        int actionX = keyX + keyWidth + KEY_GAP;
        for (int i = 0; i < lines.size(); i++) {
            String[] line = lines.get(i);
            int lineY = y + PADDING + i * LINE_H;
            context.drawText(client.textRenderer, Text.literal(line[0]), keyX, lineY, KEY_COLOR, true);
            context.drawText(client.textRenderer, Text.literal(line[1]), actionX, lineY, ACTION_COLOR, true);
        }
    }
}
