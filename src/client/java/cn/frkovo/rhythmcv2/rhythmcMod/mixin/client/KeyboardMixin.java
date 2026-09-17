package cn.frkovo.rhythmcv2.rhythmcMod.mixin.client;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioChannel;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 键盘钩子（只在「没有打开任何界面」且客户端已握手时生效）：
 * <ul>
 *   <li><b>Esc</b>：播放中 = 退出播放（发 {@code TRANSPORT_REQ STOP}），并吃掉按键不弹游戏菜单；</li>
 *   <li><b>Ctrl + 空格</b>：播放 / 暂停切换（发 {@code TRANSPORT_REQ action 0}），并吃掉按键避免顺手跳跃。</li>
 * </ul>
 * 有界面打开时两者都交给原版处理（取消输入 / 关闭面板 / 关闭 ALT 调整层）。
 */
@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void rhythmc$playbackKeys(long window, int action, KeyInput input, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS || input == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (client.getWindow() == null || client.getWindow().getHandle() != window) {
            return;
        }
        CharterAudioClient audio = CharterAudioClient.get();
        if (!audio.isHandshakeOk()) {
            return;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (!audio.engine().isPlaying()) {
                return;
            }
            audio.requestTransport(CharterAudioChannel.REQ_STOP, 1);
            ci.cancel();
            return;
        }
        if (input.key() == GLFW.GLFW_KEY_SPACE && isControlDown(client)) {
            audio.requestTransport(CharterAudioChannel.REQ_TOGGLE_PLAY, 1);
            ci.cancel();
        }
    }

    private static boolean isControlDown(MinecraftClient client) {
        return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
    }
}
