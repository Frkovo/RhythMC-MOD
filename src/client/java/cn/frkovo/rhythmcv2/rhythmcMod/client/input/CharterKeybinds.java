package cn.frkovo.rhythmcv2.rhythmcMod.client.input;

import cn.frkovo.rhythmcv2.rhythmcMod.client.hud.KeyHintHud;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioChannel;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.timeline.TimelineHud;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

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
    private static KeyBinding timelineHud;
    private static KeyBinding openEventPanel;
    private static KeyBinding hintToggle;
    private static KeyBinding toggleBounds;

    private CharterKeybinds() {
    }

    /** 右下角按键提示的一行：键位 + 说明（说明文字沿用界面中文惯例）。 */
    public record Hint(KeyBinding binding, String action) {
    }

    /** 当前可用的可配置键位提示（仅含已绑定的键位），顺序即显示顺序。 */
    public static List<Hint> activeHints() {
        List<Hint> hints = new ArrayList<>();
        addHint(hints, openEventPanel, "事件面板");
        addHint(hints, togglePlay, "播放/暂停");
        addHint(hints, prevBar, "上一小节");
        addHint(hints, nextBar, "下一小节");
        addHint(hints, loopA, "设循环A");
        addHint(hints, loopB, "设循环B");
        addHint(hints, loopClear, "清循环");
        addHint(hints, stop, "停止");
        addHint(hints, timelineHud, "时间轴开关");
        addHint(hints, toggleBounds, "判定面/边框");
        return hints;
    }

    private static void addHint(List<Hint> hints, KeyBinding binding, String action) {
        if (binding != null && !binding.isUnbound()) {
            hints.add(new Hint(binding, action));
        }
    }

    public static void register() {
        togglePlay = reg("key.rhythmc-mod.toggle_play");
        prevBar = reg("key.rhythmc-mod.prev_bar");
        nextBar = reg("key.rhythmc-mod.next_bar");
        loopA = reg("key.rhythmc-mod.loop_a");
        loopB = reg("key.rhythmc-mod.loop_b");
        loopClear = reg("key.rhythmc-mod.loop_clear");
        stop = reg("key.rhythmc-mod.stop");
        timelineHud = reg("key.rhythmc-mod.timeline_hud");
        openEventPanel = reg("key.rhythmc-mod.open_event_panel", GLFW.GLFW_KEY_G);
        hintToggle = reg("key.rhythmc-mod.hint_toggle");
        toggleBounds = reg("key.rhythmc-mod.toggle_bounds", GLFW.GLFW_KEY_B);

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
            while (timelineHud.wasPressed()) {
                TimelineHud.toggle();
            }
            while (openEventPanel.wasPressed()) {
                audio.requestEventList(audio.eventState().snapshot().channel());
            }
            while (hintToggle.wasPressed()) {
                KeyHintHud.toggle();
            }
            while (toggleBounds.wasPressed()) {
                // 开关状态以服务端下发的 BOUNDS_DATA 为准（非 glob 时插件会拒绝，本地不会误翻）
                audio.requestBounds(!audio.boundsData().on());
            }
        });
    }

    private static KeyBinding reg(String translationKey) {
        return reg(translationKey, GLFW.GLFW_KEY_UNKNOWN);
    }

    private static KeyBinding reg(String translationKey, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey, InputUtil.Type.KEYSYM, defaultKey, CATEGORY));
    }
}
