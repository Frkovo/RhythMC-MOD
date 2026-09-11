package cn.frkovo.rhythmcv2.rhythmcMod.client.input;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioChannel;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Transport 键位：所有动作都发 TRANSPORT_REQ(10) 交插件执行（协议 §11.5），不本地处理。
 * 默认未绑定（GLFW_KEY_UNKNOWN），玩家在「控制」里自行设置。
 */
public final class CharterKeybinds {

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("rhythmc-mod", "transport"));

    private static KeyBinding togglePlay;
    private static KeyBinding prevBar;
    private static KeyBinding nextBar;
    private static KeyBinding loopA;
    private static KeyBinding loopB;
    private static KeyBinding loopClear;
    private static KeyBinding stop;

    private CharterKeybinds() {
    }

    public static void register() {
        togglePlay = reg("key.rhythmc-mod.toggle_play");
        prevBar = reg("key.rhythmc-mod.prev_bar");
        nextBar = reg("key.rhythmc-mod.next_bar");
        loopA = reg("key.rhythmc-mod.loop_a");
        loopB = reg("key.rhythmc-mod.loop_b");
        loopClear = reg("key.rhythmc-mod.loop_clear");
        stop = reg("key.rhythmc-mod.stop");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            CharterAudioClient audio = CharterAudioClient.get();
            while (togglePlay.wasPressed()) {
                audio.requestTransport(CharterAudioChannel.REQ_TOGGLE_PLAY, 1);
            }
            while (prevBar.wasPressed()) {
                audio.requestTransport(CharterAudioChannel.REQ_PREV_BAR, 1);
            }
            while (nextBar.wasPressed()) {
                audio.requestTransport(CharterAudioChannel.REQ_NEXT_BAR, 1);
            }
            while (loopA.wasPressed()) {
                audio.requestTransport(CharterAudioChannel.REQ_LOOP_A, 1);
            }
            while (loopB.wasPressed()) {
                audio.requestTransport(CharterAudioChannel.REQ_LOOP_B, 1);
            }
            while (loopClear.wasPressed()) {
                audio.requestTransport(CharterAudioChannel.REQ_LOOP_CLEAR, 1);
            }
            while (stop.wasPressed()) {
                audio.requestTransport(CharterAudioChannel.REQ_STOP, 1);
            }
        });
    }

    private static KeyBinding reg(String translationKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
    }
}
