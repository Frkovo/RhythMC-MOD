package cn.frkovo.rhythmcv2.rhythmcMod.client.edit;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 属性面板 UI 状态（位置）持久化：{@code config/rhythmc-mod-editor.properties}，跨重启保留。
 */
public final class EditorUiConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("RhythMC-Charter");
    private static final String FILE_NAME = "rhythmc-mod-editor.properties";
    private static final int UNSET = Integer.MIN_VALUE;

    private static boolean loaded;
    private static int panelX = UNSET;
    private static int panelY = UNSET;
    private static int eventX = UNSET;
    private static int eventY = UNSET;

    private EditorUiConfig() {
    }

    public static boolean hasPosition() {
        ensureLoaded();
        return panelX != UNSET && panelY != UNSET;
    }

    public static int panelX() {
        ensureLoaded();
        return panelX;
    }

    public static int panelY() {
        ensureLoaded();
        return panelY;
    }

    public static boolean hasEventPosition() {
        ensureLoaded();
        return eventX != UNSET && eventY != UNSET;
    }

    public static int eventX() {
        ensureLoaded();
        return eventX;
    }

    public static int eventY() {
        ensureLoaded();
        return eventY;
    }

    public static void savePosition(int x, int y) {
        panelX = x;
        panelY = y;
        loaded = true;
        store();
    }

    public static void saveEventPosition(int x, int y) {
        eventX = x;
        eventY = y;
        loaded = true;
        store();
    }

    private static void store() {
        Properties props = new Properties();
        if (panelX != UNSET) {
            props.setProperty("panelX", Integer.toString(panelX));
            props.setProperty("panelY", Integer.toString(panelY));
        }
        if (eventX != UNSET) {
            props.setProperty("eventX", Integer.toString(eventX));
            props.setProperty("eventY", Integer.toString(eventY));
        }
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "RhythMC editor UI state");
            }
        } catch (IOException e) {
            LOGGER.warn("[charter] 保存编辑器 UI 状态失败: {}", e.getMessage());
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            panelX = parseInt(props, "panelX");
            panelY = parseInt(props, "panelY");
            eventX = parseInt(props, "eventX");
            eventY = parseInt(props, "eventY");
        } catch (IOException e) {
            panelX = UNSET;
            panelY = UNSET;
            eventX = UNSET;
            eventY = UNSET;
        }
    }

    private static int parseInt(Properties props, String key) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return UNSET;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return UNSET;
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
