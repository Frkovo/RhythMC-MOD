package cn.frkovo.rhythmcv2.rhythmcMod.client.input;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.timeline.TimelineAdjustScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

/**
 * 视图输入：
 * <ul>
 *   <li>按住 ALT：呼出鼠标光标（透明不暂停的 {@link TimelineAdjustScreen}），松开自动关闭；</li>
 *   <li>SHIFT+滚轮：世界网格缩放（±1 级，由插件执行）。</li>
 * </ul>
 * 滚轮钩子走 {@code MouseMixin}（全局，不受 GUI 影响）；调整层内的滚轮由 Screen 自行处理。
 */
public final class ViewInput {

    private ViewInput() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.currentScreen != null) {
                return;
            }
            if (!altHeld(client)) {
                return;
            }
            CharterAudioClient audio = CharterAudioClient.get();
            if (!audio.chartMeta().hasData() && !audio.engine().isLoaded()) {
                return;
            }
            client.setScreen(new TimelineAdjustScreen());
        });
    }

    public static boolean altHeld(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        Window window = client.getWindow();
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    public static boolean shiftHeld(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        Window window = client.getWindow();
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}
