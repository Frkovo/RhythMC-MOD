package cn.frkovo.rhythmcv2.rhythmcMod.client.edit;

import cn.frkovo.rhythmcv2.rhythmcMod.client.input.EditInput;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioChannel;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.EditState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.timeline.TimelineGrid;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * 音符属性面板（mod 侧，中键选中后由 EDIT_STATE 自动打开）：
 * 类型按钮 + Beat/位置/缩放/旋转；每个数值可点击就地变成输入框（Enter 提交，Esc 取消）。
 * 面板可拖动（位置跨重启保留）；透明、不暂停；改动实时 APPLY（客户端每 tick 节流一帧）。
 */
public final class NoteEditScreen extends Screen {

    private static final int BG = 0xE0101010;
    private static final int BORDER = 0xFF808080;
    private static final int LABEL_COLOR = 0xFFBBBBBB;
    private static final int LABEL_LOCKED = 0xFF666666;
    private static final int VALUE_COLOR = 0xFFFFFFFF;
    private static final int VALUE_EDIT_COLOR = 0xFF55FF55;
    private static final int VALUE_LOCKED = 0xFF777777;
    private static final int TRACK_COLOR = 0xFF303030;
    private static final int TRACK_FILL_COLOR = 0xFF3A7A3A;
    private static final int TRACK_LOCKED = 0xFF262626;
    private static final int TYPE_BG = 0xFF404040;
    private static final int TYPE_BG_ACTIVE = 0xFF2E7D32;
    private static final int TYPE_BG_DISABLED = 0xFF242424;
    private static final int BUTTON_BG = 0xFF404040;
    private static final int BUTTON_BG_ACTIVE = 0xFF2E7D32;
    private static final int BUTTON_BG_DISABLED = 0xFF242424;
    private static final int HINT_COLOR = 0xFF888888;
    private static final int HEADER_COLOR = 0xFFAAAAAA;

    private static final String[] TYPE_NAMES = {"TAP", "LOOK", "HOLD", "DODGE"};
    private static final String[] BUTTON_NAMES = {"删除", "复制", "撤销", "重做", "关闭"};
    private static final String[] BOUNDARY_NAMES = {"设为首", "设为尾"};

    private static final int ROW_H = 16;
    private static final int ROW_GAP = 3;
    private static final int LABEL_W = 52;
    private static final int VALUE_W = 58;
    private static final int HEADER_H = 22;
    private static final int TYPE_ROW_H = 22;
    private static final int HOLD_ROW_H = 22;
    private static final int BUTTONS_H = 24;
    private static final int PADDING = 10;

    /** 一行可编辑数值字段；{@code slider=false} 表示只显示数值（如 Beat）。 */
    private record Field(String key, String label, DoubleSupplier getter, DoubleConsumer setter,
                         double min, double max, double step, boolean slider) {
    }

    private record Rect(int x0, int y0, int x1, int y1) {
        boolean contains(double x, double y) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1;
        }

        int width() {
            return x1 - x0;
        }
    }

    private final List<Field> fields = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int beatMax = 256;

    private int type;
    private double beat;
    private double posX;
    private double posY;
    private double posZ;
    private float scaleX = 1f;
    private float scaleY = 1f;
    private float scaleZ = 1f;
    private float rotX;
    private float rotY;
    private float rotZ;
    private int holdGroup = -1;
    private int holdGroupSize = 1;
    private int holdGroupIndex;
    private int holdBoundary = EditState.BOUNDARY_NONE;
    private int holdGroupManual = -1;
    private double maxHalfWidth = 2.5d;
    private double maxHalfHeight = 3.0d;
    private double beatStep = 0.25d;
    private boolean canUndo;
    private boolean canRedo;

    private boolean panelDragging;
    private int dragOffsetX;
    private int dragOffsetY;
    private String dragKey;
    private String editKey;
    private String editorFieldKey;
    private TextFieldWidget editor;

    public NoteEditScreen() {
        super(Text.literal("RhythMC 音符属性"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 透明：不变暗、不模糊，保持身后世界可见。
    }

    @Override
    protected void init() {
        panelW = Math.min(340, this.width - 16);
        rebuildFields();
        panelH = HEADER_H + TYPE_ROW_H + fields.size() * (ROW_H + ROW_GAP)
                + HOLD_ROW_H + 4 + BUTTONS_H + 12;
        if (EditorUiConfig.hasPosition()) {
            panelX = clampX(EditorUiConfig.panelX());
            panelY = clampY(EditorUiConfig.panelY());
        } else {
            panelX = (this.width - panelW) / 2;
            panelY = Math.max(8, (this.height - panelH) / 2);
        }
        beatMax = Math.max(16, lastBeat());
        if (EditorUiConfig.hasPosition()) {
            EditorUiConfig.savePosition(panelX, panelY);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (textRenderer == null) {
            return;
        }
        syncFromServer();
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG);
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
        context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, BORDER);
        context.fill(panelX, panelY, panelX + 1, panelY + panelH, BORDER);
        context.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, BORDER);

        context.drawText(textRenderer, Text.literal("音符属性  " + headerSummary()),
                panelX + PADDING, panelY + 7, HEADER_COLOR, false);

        for (int i = 0; i < TYPE_NAMES.length; i++) {
            Rect rect = typeRect(i);
            boolean active = i == type;
            boolean enabled = typeEnabled(i);
            int color = !enabled ? TYPE_BG_DISABLED : (active ? TYPE_BG_ACTIVE : TYPE_BG);
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), color);
            centered(context, TYPE_NAMES[i], rect, enabled ? VALUE_COLOR : HINT_COLOR);
        }

        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            int rowY = rowY(i);
            boolean locked = fieldLocked(field);
            context.drawText(textRenderer, Text.literal(field.label()), panelX + PADDING, rowY + 4,
                    locked ? LABEL_LOCKED : LABEL_COLOR, false);
            if (field.slider()) {
                Rect slider = sliderRect(i);
                context.fill(slider.x0(), rowY + ROW_H / 2 - 1, slider.x1(), rowY + ROW_H / 2 + 2,
                        locked ? TRACK_LOCKED : TRACK_COLOR);
                double value = field.getter().getAsDouble();
                double fraction = fraction(field, value);
                int fillTo = slider.x0() + (int) Math.round(fraction * slider.width());
                context.fill(slider.x0(), rowY + ROW_H / 2 - 1, fillTo, rowY + ROW_H / 2 + 2,
                        locked ? TRACK_LOCKED : TRACK_FILL_COLOR);
                if (!locked) {
                    context.fill(fillTo - 1, rowY + 3, fillTo + 1, rowY + ROW_H - 3, VALUE_COLOR);
                }
            }
            if (!field.key().equals(editKey)) {
                context.drawText(textRenderer, Text.literal(format(field, field.getter().getAsDouble())),
                        valueRect(i).x0(), rowY + 4, locked ? VALUE_LOCKED : VALUE_COLOR, false);
            }
        }

        // HOLD 行：头/身/尾标签 + 手动边界切换
        int holdY = holdRowY();
        context.drawText(textRenderer, Text.literal("HOLD"), panelX + PADDING, holdY + 6, LABEL_COLOR, false);
        context.drawText(textRenderer, Text.literal(holdTag()), panelX + PADDING + 44, holdY + 6,
                type == EditState.TYPE_HOLD ? VALUE_COLOR : LABEL_LOCKED, false);
        for (int i = 0; i < BOUNDARY_NAMES.length; i++) {
            Rect rect = boundaryRect(i);
            boolean enabled = type == EditState.TYPE_HOLD;
            boolean active = enabled && holdBoundary == (i == 0 ? EditState.BOUNDARY_START : EditState.BOUNDARY_END);
            int color = !enabled ? BUTTON_BG_DISABLED : (active ? BUTTON_BG_ACTIVE : BUTTON_BG);
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), color);
            centered(context, BOUNDARY_NAMES[i], rect, enabled ? VALUE_COLOR : HINT_COLOR);
        }

        for (int i = 0; i < BUTTON_NAMES.length; i++) {
            Rect rect = buttonRect(i);
            boolean enabled = buttonEnabled(i);
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), enabled ? BUTTON_BG : BUTTON_BG_DISABLED);
            centered(context, BUTTON_NAMES[i], rect, enabled ? VALUE_COLOR : HINT_COLOR);
        }

        context.drawText(textRenderer,
                Text.literal("拖标题移动 | 点数值输入 | Shift 细调 | Ctrl+Z/Y 撤销 | Esc 关闭"),
                panelX + PADDING, panelY + panelH - 11, HINT_COLOR, false);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) {
            return false;
        }
        double mx = click.x();
        double my = click.y();
        if (editor != null && editor.isMouseOver(mx, my)) {
            return super.mouseClicked(click, doubled);
        }
        // 标题栏拖动
        if (headerRect().contains(mx, my)) {
            commitEditor();
            panelDragging = true;
            dragOffsetX = (int) (mx - panelX);
            dragOffsetY = (int) (my - panelY);
            return true;
        }
        for (int i = 0; i < TYPE_NAMES.length; i++) {
            if (typeRect(i).contains(mx, my)) {
                if (typeEnabled(i)) {
                    commitEditor();
                    type = i;
                    if (type != EditState.TYPE_HOLD) {
                        holdBoundary = EditState.BOUNDARY_NONE;
                    }
                    apply();
                } else {
                    chat("仅 Y = -1 的音符可转为 HOLD（先把 Y 调到 -1）");
                }
                return true;
            }
        }
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            if (fieldLocked(field)) {
                continue;
            }
            if (valueRect(i).contains(mx, my)) {
                openEditor(field, valueRect(i));
                return true;
            }
            if (field.slider() && sliderRect(i).contains(mx, my)) {
                commitEditor();
                dragKey = field.key();
                setFromMouse(field, sliderRect(i), mx);
                return true;
            }
        }
        for (int i = 0; i < BOUNDARY_NAMES.length; i++) {
            if (boundaryRect(i).contains(mx, my)) {
                if (type != EditState.TYPE_HOLD) {
                    chat("手动边界只对 HOLD 生效");
                    return true;
                }
                int target = i == 0 ? EditState.BOUNDARY_START : EditState.BOUNDARY_END;
                holdBoundary = holdBoundary == target ? EditState.BOUNDARY_NONE : target;
                apply();
                return true;
            }
        }
        for (int i = 0; i < BUTTON_NAMES.length; i++) {
            if (buttonRect(i).contains(mx, my) && buttonEnabled(i)) {
                clickButton(i);
                return true;
            }
        }
        if (!insidePanel(mx, my)) {
            commitEditor();
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (panelDragging) {
            panelX = clampX((int) (click.x() - dragOffsetX));
            panelY = clampY((int) (click.y() - dragOffsetY));
            return true;
        }
        if (dragKey != null) {
            int index = indexOf(dragKey);
            if (index >= 0) {
                setFromMouse(fields.get(index), sliderRect(index), click.x());
            }
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (panelDragging) {
            panelDragging = false;
            EditorUiConfig.savePosition(panelX, panelY);
            return true;
        }
        if (dragKey != null) {
            dragKey = null;
            CharterAudioClient.get().flushNoteEdit();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
            commitEditor();
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (editor != null) {
                cancelEditor();
                return true;
            }
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void close() {
        commitEditor();
        CharterAudioClient.get().flushNoteEdit();
        CharterAudioClient.get().requestEdit(CharterAudioChannel.EDIT_DESELECT);
        super.close();
    }

    /** 是否有就地输入框处于聚焦（EditInput 据此让出 Ctrl+Z/Y）。 */
    public boolean isTextEditing() {
        return editor != null;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void rebuildFields() {
        fields.clear();
        fields.add(new Field("beat", "Beat", () -> beat, v -> beat = v, 0d, Math.max(16, beatMax), beatStep, false));
        fields.add(new Field("posX", "X", () -> posX, v -> posX = v, -maxHalfWidth, maxHalfWidth, 0.05d, true));
        fields.add(new Field("posY", "Y", () -> posY, v -> posY = v, -maxHalfHeight, maxHalfHeight, 0.05d, true));
        fields.add(new Field("posZ", "Z", () -> posZ, v -> posZ = v, -3d, 3d, 0.05d, true));
        fields.add(new Field("scaleX", "ScaleX", () -> (double) scaleX, v -> scaleX = (float) v, 0.1d, 3d, 0.05d, true));
        fields.add(new Field("scaleY", "ScaleY", () -> (double) scaleY, v -> scaleY = (float) v, 0.1d, 3d, 0.05d, true));
        fields.add(new Field("scaleZ", "ScaleZ", () -> (double) scaleZ, v -> scaleZ = (float) v, 0.1d, 3d, 0.05d, true));
        fields.add(new Field("rotX", "RotX", () -> (double) rotX, v -> rotX = (float) v, -180d, 180d, 0.5d, true));
        fields.add(new Field("rotY", "RotY", () -> (double) rotY, v -> rotY = (float) v, -180d, 180d, 0.5d, true));
        fields.add(new Field("rotZ", "RotZ", () -> (double) rotZ, v -> rotZ = (float) v, -180d, 180d, 0.5d, true));
        fields.add(new Field("holdGroupManual", "组号", () -> (double) holdGroupManual,
                v -> holdGroupManual = v < 0d ? -1 : (int) Math.round(Math.min(4096d, v)),
                -1d, 4096d, 1d, false));
    }

    private int lastBeat() {
        TimelineGrid grid = new TimelineGrid(CharterAudioClient.get().chartMeta());
        return grid.isUsable() ? grid.lastBeat() : 256;
    }

    private void syncFromServer() {
        EditState.Snapshot state = CharterAudioClient.get().editState().snapshot();
        if (!state.ok()) {
            return;
        }
        canUndo = state.canUndo();
        canRedo = state.canRedo();
        if (editor != null || dragKey != null || panelDragging) {
            return; // 编辑/拖动中：数值以本地为准，只更新按钮可用性
        }
        type = state.type();
        beat = state.beat();
        posX = state.posX();
        posY = state.posY();
        posZ = state.posZ();
        scaleX = state.scaleX();
        scaleY = state.scaleY();
        scaleZ = state.scaleZ();
        rotX = state.rotX();
        rotY = state.rotY();
        rotZ = state.rotZ();
        holdGroup = state.holdGroup();
        holdGroupSize = state.holdGroupSize();
        holdGroupIndex = state.holdGroupIndex();
        holdBoundary = state.holdBoundary();
        holdGroupManual = state.holdGroupManual();
        maxHalfWidth = state.maxHalfWidth();
        maxHalfHeight = state.maxHalfHeight();
        if (state.beatStep() > 0 && Math.abs(state.beatStep() - beatStep) > 1.0E-9) {
            beatStep = state.beatStep();
        }
        rebuildFields();
    }

    private void apply() {
        CharterAudioClient.get().applyNoteEdit(new EditState.Snapshot(true, "", type, beat,
                posX, posY, posZ, scaleX, scaleY, scaleZ, rotX, rotY, rotZ,
                holdGroup, holdGroupSize, holdGroupIndex, holdBoundary, holdGroupManual,
                maxHalfWidth, maxHalfHeight, beatStep, canUndo, canRedo));
    }

    private void openEditor(Field field, Rect rect) {
        commitEditor();
        editKey = field.key();
        editorFieldKey = field.key();
        TextFieldWidget widget = new TextFieldWidget(textRenderer, rect.x0(), rect.y0() + 1,
                Math.max(48, rect.width()), ROW_H - 2, Text.literal(field.label()));
        widget.setMaxLength(12);
        widget.setDrawsBackground(false);
        widget.setEditableColor(VALUE_EDIT_COLOR);
        widget.setText(editorText(field, field.getter().getAsDouble()));
        widget.setTextPredicate(text -> text.isEmpty() || text.matches("-?\\d*\\.?\\d*"));
        widget.setFocused(true);
        widget.setCursorToEnd(false);
        editor = widget;
        addDrawableChild(widget);
    }

    private void commitEditor() {
        if (editor == null) {
            return;
        }
        String key = editorFieldKey;
        String text = editor.getText() == null ? "" : editor.getText().trim();
        remove(editor);
        editor = null;
        editorFieldKey = null;
        editKey = null;
        int index = indexOf(key);
        if (index < 0 || text.isEmpty()) {
            return;
        }
        Field field = fields.get(index);
        try {
            double value = Double.parseDouble(text);
            if (Double.isFinite(value)) {
                field.setter().accept(Math.max(field.min(), Math.min(field.max(), value)));
                apply();
            }
        } catch (NumberFormatException ignored) {
            // 非法输入：丢弃，保留原值
        }
    }

    private void cancelEditor() {
        if (editor == null) {
            return;
        }
        remove(editor);
        editor = null;
        editorFieldKey = null;
        editKey = null;
    }

    private void setFromMouse(Field field, Rect slider, double mouseX) {
        double fraction = slider.width() <= 0 ? 0d
                : Math.max(0d, Math.min(1d, (mouseX - slider.x0()) / slider.width()));
        double raw = field.min() + fraction * (field.max() - field.min());
        double step = field.step();
        if (step > 0 && shiftHeld()) {
            step *= 0.1d;
        }
        double value = step > 0 ? Math.round(raw / step) * step : raw;
        field.setter().accept(value);
        apply();
    }

    private static boolean shiftHeld() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getWindow() != null && EditInput.shiftHeld(client.getWindow());
    }

    private double fraction(Field field, double value) {
        double span = field.max() - field.min();
        return span <= 0 ? 0d : Math.max(0d, Math.min(1d, (value - field.min()) / span));
    }

    private void clickButton(int index) {
        CharterAudioClient audio = CharterAudioClient.get();
        switch (index) {
            case 0 -> audio.requestEdit(CharterAudioChannel.EDIT_DELETE_SELECTED);
            case 1 -> audio.requestEdit(CharterAudioChannel.EDIT_CLONE_TO_NEXT);
            case 2 -> audio.requestEdit(CharterAudioChannel.EDIT_UNDO);
            case 3 -> audio.requestEdit(CharterAudioChannel.EDIT_REDO);
            default -> close();
        }
    }

    private boolean buttonEnabled(int index) {
        return switch (index) {
            case 2 -> canUndo;
            case 3 -> canRedo;
            default -> true;
        };
    }

    /** HOLD 类型只有在音符本身位于 Y = -1（HOLD 轨道）时才可切换。 */
    private boolean typeEnabled(int index) {
        if (index != EditState.TYPE_HOLD) {
            return true;
        }
        return type == EditState.TYPE_HOLD || Math.abs(posY + 1d) < 1.0E-6d;
    }

    /** HOLD 的 Y 由链决定，锁定不可编辑；组号只对 HOLD 生效。 */
    private boolean fieldLocked(Field field) {
        if (type != EditState.TYPE_HOLD) {
            return field.key().equals("holdGroupManual");
        }
        return field.key().equals("posY");
    }

    private String headerSummary() {
        String typeName = TYPE_NAMES[Math.max(0, Math.min(TYPE_NAMES.length - 1, type))];
        boolean hold = type == EditState.TYPE_HOLD;
        return hold ? typeName + "  " + holdTag() : typeName;
    }

    /** 组号 + 头/身/尾（只读展示，来自链分组）。 */
    private String holdTag() {
        if (type != EditState.TYPE_HOLD) {
            return "—";
        }
        if (holdGroup < 0 || holdGroupSize <= 1) {
            return holdGroupManual >= 0 ? "组 #" + holdGroupManual + " 单" : "单 1/1";
        }
        String part = holdGroupIndex <= 0 ? "头" : (holdGroupIndex >= holdGroupSize - 1 ? "尾" : "身");
        return "组 #" + holdGroup + " " + part + " " + (holdGroupIndex + 1) + "/" + holdGroupSize;
    }

    private String format(Field field, double value) {
        if (field.key().equals("holdGroupManual")) {
            return value < 0d ? "自动" : String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** 就地输入框的初始文本（可直接解析，不能用「自动」这种展示态）。 */
    private static String editorText(Field field, double value) {
        return field.key().equals("holdGroupManual")
                ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.3f", value);
    }

    private int indexOf(String key) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private int rowY(int index) {
        int rowsTop = panelY + HEADER_H + TYPE_ROW_H;
        return rowsTop + index * (ROW_H + ROW_GAP);
    }

    private int holdRowY() {
        return panelY + HEADER_H + TYPE_ROW_H + fields.size() * (ROW_H + ROW_GAP);
    }

    private int buttonsY() {
        return holdRowY() + HOLD_ROW_H + 4;
    }

    private Rect headerRect() {
        return new Rect(panelX, panelY, panelX + panelW, panelY + HEADER_H);
    }

    private Rect typeRect(int index) {
        int gap = 4;
        int total = panelW - PADDING * 2;
        int w = (total - gap * 3) / 4;
        int x = panelX + PADDING + index * (w + gap);
        return new Rect(x, panelY + HEADER_H, x + w, panelY + HEADER_H + TYPE_ROW_H - 4);
    }

    private Rect sliderRect(int index) {
        int x = panelX + PADDING + LABEL_W + 4;
        int w = panelW - PADDING * 2 - LABEL_W - 4 - VALUE_W - 6;
        return new Rect(x, rowY(index), x + w, rowY(index) + ROW_H);
    }

    private Rect valueRect(int index) {
        int x = panelX + PADDING + LABEL_W + 4
                + Math.max(0, panelW - PADDING * 2 - LABEL_W - 4 - VALUE_W - 6) + 6;
        return new Rect(x, rowY(index), x + VALUE_W, rowY(index) + ROW_H);
    }

    private Rect boundaryRect(int index) {
        int gap = 4;
        int w = 54;
        int x0 = panelX + panelW - PADDING - (w * 2 + gap);
        int x = x0 + index * (w + gap);
        int y = holdRowY() + 2;
        return new Rect(x, y, x + w, y + 18);
    }

    private Rect buttonRect(int index) {
        int gap = 4;
        int total = panelW - PADDING * 2;
        int w = (total - gap * 4) / 5;
        int x = panelX + PADDING + index * (w + gap);
        int y = buttonsY();
        return new Rect(x, y, x + w, y + BUTTONS_H - 4);
    }

    private boolean insidePanel(double x, double y) {
        return x >= panelX && x <= panelX + panelW && y >= panelY && y <= panelY + panelH;
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(Math.max(0, this.width - panelW), x));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(Math.max(0, this.height - panelH), y));
    }

    private void centered(DrawContext context, String text, Rect rect, int color) {
        int width = textRenderer.getWidth(text);
        context.drawText(textRenderer, Text.literal(text),
                (rect.x0() + rect.x1() - width) / 2, rect.y0() + 4, color, false);
    }

    private static void chat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal("§e[RhythMC] " + message));
        }
    }
}
