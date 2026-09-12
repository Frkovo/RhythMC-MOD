package cn.frkovo.rhythmcv2.rhythmcMod.mixin.client;

import cn.frkovo.rhythmcv2.rhythmcMod.client.input.ViewInput;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 全局滚轮钩子：SHIFT+滚轮 = 世界网格缩放（发 VIEW_ZOOM，取消原版滚轮以免切换物品栏）。
 * ALT 调整层打开时（currentScreen != null）不拦截，滚轮交给 Screen 调整 HUD 缩放。
 */
@Mixin(Mouse.class)
public class MouseMixin {

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
}
