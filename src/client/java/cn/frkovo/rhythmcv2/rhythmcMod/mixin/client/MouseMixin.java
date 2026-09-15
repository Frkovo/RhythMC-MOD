package cn.frkovo.rhythmcv2.rhythmcMod.mixin.client;

import cn.frkovo.rhythmcv2.rhythmcMod.client.input.ViewInput;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioChannel;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 鼠标钩子：
 * <ul>
 *   <li>全局滚轮：SHIFT+滚轮 = 世界网格缩放（发 VIEW_ZOOM，取消原版滚轮以免切换物品栏）；</li>
 *   <li>中键：编辑器会话中 = 选中准星音符并请求属性面板（取消原版选方块）。</li>
 * </ul>
 * 任意 GUI 打开（{@code currentScreen != null}）时不拦截。
 */
@Mixin(Mouse.class)
public class MouseMixin {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("RhythMC-Charter");

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void rhythmc$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null || vertical == 0d) {
            return;
        }
        if (!ViewInput.shiftHeld(client)) {
            return;
        }
        CharterAudioClient.get().requestViewZoom(vertical > 0d ? 1 : -1);
        ci.cancel();
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void rhythmc$onMouseButton(long window, MouseInput input, int action, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return;
        }
        if (action == GLFW.GLFW_PRESS && input != null) {
            LOGGER.info("[charter_audio] mouse press button={} action={}", input.button(), action);
        }
        if (action != GLFW.GLFW_PRESS || input == null || input.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            return;
        }
        CharterAudioClient audio = CharterAudioClient.get();
        if (!audio.chartMeta().hasData()) {
            return; // 不在编辑器会话中：放行原版中键行为
        }
        audio.requestEdit(CharterAudioChannel.EDIT_OPEN_NOTE_GUI);
        ci.cancel();
    }
}
