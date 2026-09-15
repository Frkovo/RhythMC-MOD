package cn.frkovo.rhythmcv2.rhythmcMod.client.input;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioChannel;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.EventState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.edit.EventEditScreen;
import cn.frkovo.rhythmcv2.rhythmcMod.client.edit.NoteEditScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

/**
 * 编辑器输入：
 * <ul>
 *   <li>Ctrl+Z 撤销；Ctrl+Y 与 Ctrl+Shift+Z 重做；</li>
 *   <li>按服务端 EDIT_STATE 自动打开 / 关闭属性面板。</li>
 * </ul>
 * 组合键用原始按键轮询 + 自持边沿检测（Minecraft 键位不支持组合键）；任意 GUI 打开时不触发。
 */
public final class EditInput {

    private static boolean zWasDown;
    private static boolean yWasDown;

    private EditInput() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            syncScreen(client);
            if (client.player == null) {
                zWasDown = false;
                yWasDown = false;
                return;
            }
            // 属性面板/事件面板打开时仍允许撤销/重做（就地输入框聚焦中除外）
            if (client.currentScreen != null
                    && (!(client.currentScreen instanceof NoteEditScreen noteScreen) || noteScreen.isTextEditing())
                    && (!(client.currentScreen instanceof EventEditScreen eventScreen) || eventScreen.isTextEditing())) {
                zWasDown = false;
                yWasDown = false;
                return;
            }
            Window window = client.getWindow();
            if (window == null) {
                return;
            }
            boolean ctrl = ctrlHeld(window);
            boolean shift = shiftHeld(window);
            boolean zDown = ctrl && keyDown(window, GLFW.GLFW_KEY_Z);
            boolean yDown = ctrl && keyDown(window, GLFW.GLFW_KEY_Y);

            CharterAudioClient audio = CharterAudioClient.get();
            if (zDown && !zWasDown) {
                if (shift) {
                    audio.requestEdit(CharterAudioChannel.EDIT_REDO);
                } else {
                    audio.requestEdit(CharterAudioChannel.EDIT_UNDO);
                }
            }
            if (yDown && !yWasDown) {
                audio.requestEdit(CharterAudioChannel.EDIT_REDO);
            }
            zWasDown = zDown;
            yWasDown = yDown;
        });
    }

    /** 服务端选中/事件状态驱动的面板开关（面板自己处理 Esc/关闭时的取消选中）。 */
    private static void syncScreen(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        CharterAudioClient audio = CharterAudioClient.get();
        boolean noteOpen = audio.editState().snapshot().ok();
        EventState.Snapshot event = audio.eventState().snapshot();
        Screen current = client.currentScreen;
        if (noteOpen) {
            if (current == null) {
                client.setScreen(new NoteEditScreen());
                return;
            }
            if (!(current instanceof NoteEditScreen)) {
                return;
            }
        } else if (current instanceof NoteEditScreen) {
            client.setScreen(null);
            current = null;
        }
        if (current instanceof EventEditScreen) {
            if (!event.ok() || !event.panel()) {
                client.setScreen(null);
            }
            return;
        }
        if (event.ok() && event.panel() && current == null) {
            client.setScreen(new EventEditScreen());
        }
    }

    private static boolean keyDown(Window window, int key) {
        return InputUtil.isKeyPressed(window, key);
    }

    public static boolean ctrlHeld(Window window) {
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    public static boolean shiftHeld(Window window) {
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}
