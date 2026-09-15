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

    public static void savePosition(int x, int y) {
        panelX = x;
        panelY = y;
        loaded = true;
        Properties props = new Properties();
        props.setProperty("panelX", Integer.toString(x));
        props.setProperty("panelY", Integer.toString(y));
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
            panelX = Integer.parseInt(props.getProperty("panelX"));
            panelY = Integer.parseInt(props.getProperty("panelY"));
        } catch (IOException | NumberFormatException e) {
            panelX = UNSET;
            panelY = UNSET;
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
