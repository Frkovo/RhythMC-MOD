package cn.frkovo.rhythmcv2.rhythmcMod.client.edit;

import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioChannel;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.CharterAudioClient;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.Easing;
import cn.frkovo.rhythmcv2.rhythmcMod.client.net.EventState;
import cn.frkovo.rhythmcv2.rhythmcMod.client.timeline.TimelineGrid;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * Track 事件面板（`G` 键 / `/charter events` 打开）。
 *
 * <p>10 条通道（流速 / 位移 / 缩放 / 旋转），每条通道由若干事件段 `[起始拍, 结束拍] : 起始值 → 结束值 + 缓动` 组成；
 * 起止拍相同 = 跳变。每条通道默认播种一段覆盖全曲的中性值段。</p>
 *
 * <p>交互：点列表行展开该段的完整字段（再点同一行收起），字段可直接点开输入；
 * 面板整体可滚动（滚轮 / 右侧滑块），顶部标题栏固定并作为拖动把手；
 * 「试听本段」带音乐从该段前一小节播到后一小节，结束后自动回到原位并重开面板。</p>
 */
public final class EventEditScreen extends Screen {

    private static final int BG = 0xE8101010;
    private static final int BORDER = 0xFF808080;
    private static final int LABEL_COLOR = 0xFFCCCCCC;
    private static final int VALUE_COLOR = 0xFFFFFFFF;
    private static final int VALUE_EDIT_COLOR = 0xFF55FF55;
    private static final int VALUE_JUMP_COLOR = 0xFFFFAA55;
    private static final int HINT_COLOR = 0xFF8A8A8A;
    private static final int DESC_COLOR = 0xFF9FBFA0;
    private static final int HEADER_COLOR = 0xFFFFE08A;
    private static final int ROW_BG = 0xFF1B1B1B;
    private static final int ROW_BG_SELECTED = 0xFF2A4429;
    private static final int CARD_BG = 0xFF141414;
    private static final int CH_BG = 0xFF3A3A3A;
    private static final int CH_BG_ACTIVE = 0xFF2E7D32;
    private static final int BUTTON_BG = 0xFF404040;
    private static final int BUTTON_BG_ACTIVE = 0xFF2E7D32;
    private static final int BUTTON_BG_DISABLED = 0xFF242424;
    private static final int SCROLL_TRACK = 0xFF202020;
    private static final int SCROLL_THUMB = 0xFF6A6A6A;

    private static final int PADDING = 10;
    private static final int LINE_H = 12;
    private static final int CH_H = 16;
    private static final int ROW_H = 16;
    private static final int FIELD_H = 15;
    private static final int BUTTON_H = 17;
    private static final int CH_PER_ROW = 5;
    private static final int MAX_LIST_ROWS = 64;
    private static final int LABEL_W = 68;
    private static final int FIELD_W = 84;
    /** 固定头部（标题 + 游标/当前值）的高度，滚动时这部分不动。 */
    private static final int HEADER_H = 32;
    private static final int SCROLL_STEP = 18;
    private static final int MARGIN = 8;
    private static final int CARD_MIN_EXTRA = 150;

    /** 通道名。 */
    private static final String[] CH_LABELS = {
            "流速", "位移 X", "位移 Y", "位移 Z", "缩放 X",
            "缩放 Y", "缩放 Z", "旋转 X", "旋转 Y", "旋转 Z"};

    /** 通道英文键（与命令一致）。 */
    private static final String[] CH_KEYS = {
            "speed", "x", "y", "z", "sx", "sy", "sz", "rx", "ry", "rz"};

    /** 数值单位。 */
    private static final String[] CH_UNITS = {
            "×", "格", "格", "格", "倍", "倍", "倍", "度", "度", "度"};

    /** 效果句里的主语句。 */
    private static final String[] CH_SUBJECTS = {
            "流速", "轨道 X 位移", "轨道 Y 位移", "轨道 Z 位移",
            "轨道 X 缩放", "轨道 Y 缩放", "轨道 Z 缩放",
            "轨道 X 旋转", "轨道 Y 旋转", "轨道 Z 旋转"};

    /** 通道说明（最多 2 行；空串不绘制）。 */
    private static final String[][] CH_DESC = {
            {"为常态流速的倍率：1.0 = 常态（官方谱面约 1.0–1.3）。", "正值向前、负值倒流；数字越大，音符来得越快。"},
            {"轨道沿 X 轴平移，单位 格。", "正值向右，负值向左。"},
            {"轨道沿 Y 轴平移，单位 格。", "正值向上，负值向下。"},
            {"轨道沿 Z 轴平移，单位 格。", "正值靠近判定面，负值远离。"},
            {"轨道 X 轴缩放倍数，1.0 = 原始大小。", ""},
            {"轨道 Y 轴缩放倍数，1.0 = 原始大小。", ""},
            {"轨道 Z 轴缩放倍数，1.0 = 原始大小。", ""},
            {"轨道绕 X 轴旋转，单位 度。", "正值与负值分别为两个旋转方向。"},
            {"轨道绕 Y 轴旋转，单位 度。", "正值与负值分别为两个旋转方向。"},
            {"轨道绕 Z 轴旋转，单位 度。", "正值与负值分别为两个旋转方向。"}};

    private record Rect(int x0, int y0, int x1, int y1) {
        boolean contains(double x, double y) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1;
        }

        int width() {
            return x1 - x0;
        }
    }

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentH;
    private int scrollOffset;
    private int scrollMax;

    private boolean panelDragging;
    private boolean scrollDragging;
    private int dragOffsetX;
    private int dragOffsetY;

    private int selected = -1;
    private boolean easingPage;
    /** 上次应用的服务端「选中段号」，避免每帧重复覆盖本地选择。 */
    private int lastSelectIndex = -1;
    private int easingBase;

    private String editorKey;
    private TextFieldWidget editor;

    public EventEditScreen() {
        super(Text.literal("RhythMC Track 事件"));
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
        panelW = Math.min(470, this.width - 16);
        if (EditorUiConfig.hasEventPosition()) {
            panelX = clampX(EditorUiConfig.eventX());
            panelY = clampY(EditorUiConfig.eventY());
        } else {
            panelX = Math.max(0, (this.width - panelW) / 2);
            panelY = MARGIN;
        }
        panelH = 20;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (textRenderer == null) {
            return;
        }
        EventState.Snapshot state = CharterAudioClient.get().eventState().snapshot();
        int pending = CharterAudioClient.get().consumePendingSelect();
        if (pending >= 0) {
            selected = pending;
        }
        // 服务端要求选中某段（曲线 hover 的「编辑」按钮）
        int fromServer = state.selectIndex();
        if (fromServer >= 0 && fromServer != lastSelectIndex) {
            selected = fromServer;
        }
        lastSelectIndex = fromServer;
        clampSelection(state);
        Layout layout = layoutFor(state);
        drawFrame(context, layout);
        // 固定头部（不随滚动移动）：标题 + 游标/当前值，同时是拖动把手
        context.drawText(textRenderer,
                Text.literal("Track #" + state.trackId() + " · " + CH_LABELS[channel(state)]
                        + "（" + CH_KEYS[channel(state)] + "）"),
                panelX + PADDING, layout.titleY() + scrollOffset, HEADER_COLOR, false);
        context.drawText(textRenderer,
                Text.literal(String.format(Locale.ROOT, "游标 %s 拍　当前值 %s",
                        fmt(cursorBeat()), channelValueText(state))),
                panelX + PADDING, layout.infoY() + scrollOffset, VALUE_COLOR, false);

        context.enableScissor(panelX + 1, panelY + HEADER_H, panelX + panelW - 1, panelY + panelH - 1);
        if (easingPage) {
            renderEasingPage(context, layout, state);
        } else {
            renderChannels(context, layout, state);
            renderList(context, layout, state);
        }
        context.disableScissor();
        drawScrollbar(context);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderChannels(DrawContext context, Layout layout, EventState.Snapshot state) {
        for (int i = 0; i < EventState.CHANNEL_COUNT; i++) {
            Rect rect = layout.channels()[i];
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(),
                    i == channel(state) ? CH_BG_ACTIVE : CH_BG);
            centered(context, CH_LABELS[i], rect, VALUE_COLOR);
        }
        for (int line = 0; line < CH_DESC[channel(state)].length; line++) {
            String text = CH_DESC[channel(state)][line];
            if (text.isEmpty()) {
                continue;
            }
            context.drawText(textRenderer, Text.literal(text),
                    panelX + PADDING, layout.descY() + line * LINE_H, DESC_COLOR, false);
        }
    }

    private void renderList(DrawContext context, Layout layout, EventState.Snapshot state) {
        List<EventState.Event> events = state.events();
        context.drawText(textRenderer, Text.literal(events.isEmpty()
                        ? "该通道暂无事件段"
                        : "事件段（共 " + events.size() + " 段，点击展开/收起）"),
                panelX + PADDING, layout.listHeaderY(), HEADER_COLOR, false);
        for (int row = 0; row < layout.listCount() && row < events.size(); row++) {
            renderListRow(context, layout, events, row, channel(state));
        }

        if (selectedEvent(state) != null) {
            renderCard(context, layout, state, selectedEvent(state));
        } else {
            context.drawText(textRenderer, Text.literal("选择一个事件段以编辑"),
                    panelX + PADDING, layout.cardTop() + 4, HINT_COLOR, false);
        }

        renderCreateArea(context, layout, state);
        renderFooter(context, layout, state);
        renderPreviewToggle(context, layout, state);
        context.drawText(textRenderer,
                Text.literal("试听=完整效果+音乐 · 只预览=只看当前通道（无音乐）· 点击数值编辑 · Esc 关闭"),
                panelX + PADDING, layout.hintY(), HINT_COLOR, false);
    }

    private void renderListRow(DrawContext context, Layout layout, List<EventState.Event> events,
                               int index, int channel) {
        Rect area = layout.listRows()[index];
        EventState.Event event = events.get(index);
        boolean sel = index == selected;
        boolean jump = index + 1 < events.size()
                && Math.abs(event.endValue() - events.get(index + 1).startValue()) > 1.0E-6;
        context.fill(area.x0(), area.y0(), area.x1(), area.y1(), sel ? ROW_BG_SELECTED : ROW_BG);
        context.drawText(textRenderer, Text.literal(sel ? "▾" : "▸"), area.x0() + 4, area.y0() + 4,
                sel ? VALUE_COLOR : HINT_COLOR, false);
        context.drawText(textRenderer, Text.literal(String.valueOf(index + 1)), area.x0() + 16, area.y0() + 4,
                sel ? VALUE_COLOR : LABEL_COLOR, false);
        String range = String.format(Locale.ROOT, "第 %s → %s 拍",
                fmt(event.startBeat()),
                index + 1 < events.size() ? fmt(event.endBeat()) : fmt(event.endBeat()) + "（曲尾）");
        context.drawText(textRenderer, Text.literal(range), area.x0() + 30, area.y0() + 4, VALUE_COLOR, false);
        String values = String.format(Locale.ROOT, "%s → %s %s", fmt(event.startValue()),
                fmt(event.endValue()), CH_UNITS[channel]);
        context.drawText(textRenderer, Text.literal(values), area.x0() + 216, area.y0() + 4,
                jump ? VALUE_JUMP_COLOR : VALUE_COLOR, false);
        context.drawText(textRenderer,
                Text.literal((jump ? "⟂ " : "") + easingChinese(event.easing())),
                area.x0() + 352, area.y0() + 4, jump ? VALUE_JUMP_COLOR : LABEL_COLOR, false);
    }

    private void renderCard(DrawContext context, Layout layout, EventState.Snapshot state,
                            EventState.Event event) {
        context.fill(panelX + PADDING - 3, layout.cardTop(), panelX + panelW - PADDING + 3,
                layout.cardBottom(), CARD_BG);
        context.drawText(textRenderer,
                Text.literal(String.format(Locale.ROOT, "第 %d / %d 段（点击上方该行可收起）",
                        selected + 1, state.events().size())),
                panelX + PADDING, layout.cardTitleY(), HEADER_COLOR, false);

        String unit = CH_UNITS[channel(state)];
        String subject = CH_SUBJECTS[channel(state)];
        drawFieldRow(context, layout.cardField(0), "起始拍", "start", event);
        context.drawText(textRenderer, Text.literal("拍"), layout.cardField(0).x1() + 4,
                layout.cardField(0).y0() + 4, LABEL_COLOR, false);
        drawFieldRow(context, layout.cardField(1), "结束拍", "end", event);
        context.drawText(textRenderer, Text.literal("拍" + sentinelNote(state, event)),
                layout.cardField(1).x1() + 4, layout.cardField(1).y0() + 4, LABEL_COLOR, false);
        drawFieldRow(context, layout.cardField(2), "起始值", "a", event);
        context.drawText(textRenderer, Text.literal(unit), layout.cardField(2).x1() + 4,
                layout.cardField(2).y0() + 4, LABEL_COLOR, false);
        drawFieldRow(context, layout.cardField(3), "结束值", "b", event);
        context.drawText(textRenderer, Text.literal(unit), layout.cardField(3).x1() + 4,
                layout.cardField(3).y0() + 4, LABEL_COLOR, false);

        Rect easingArea = layout.cardEasing();
        context.drawText(textRenderer, Text.literal("缓动"), panelX + PADDING, easingArea.y0() + 4,
                LABEL_COLOR, false);
        context.fill(easingArea.x0(), easingArea.y0(), easingArea.x1(), easingArea.y1(), BUTTON_BG);
        context.drawText(textRenderer, Text.literal(easingChinese(event.easing()) + "  "
                        + Easing.name(event.easing())),
                easingArea.x0() + 4, easingArea.y0() + 4, VALUE_COLOR, false);

        context.drawText(textRenderer, Text.literal("效果："), panelX + PADDING,
                layout.cardEffectY(), LABEL_COLOR, false);
        context.drawText(textRenderer, Text.literal(effectSentence(state, event)),
                panelX + PADDING, layout.cardEffectY() + LINE_H, DESC_COLOR, false);

        for (int i = 0; i < layout.cardButtons().length; i++) {
            Rect rect = layout.cardButtons()[i];
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), BUTTON_BG);
            centered(context, cardButtonLabel(i, event, state.events().size()), rect, VALUE_COLOR);
        }
        for (int i = 0; i < layout.stepButtons().length; i++) {
            Rect rect = layout.stepButtons()[i];
            boolean enabled = i == 0 ? selected > 0 : selected < state.events().size() - 1;
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), enabled ? BUTTON_BG : BUTTON_BG_DISABLED);
            centered(context, i == 0 ? "◀ 上一段" : "下一段 ▶", rect, enabled ? VALUE_COLOR : HINT_COLOR);
        }
    }

    /** 卡片按钮 5 个：起点取游标 / 终点取游标 / 接上前段 / 在游标处切开 / 并入上一段（或重置通道）。 */
    private static String cardButtonLabel(int index, EventState.Event event, int count) {
        return switch (index) {
            case 0 -> "起点=游标";
            case 1 -> "终点=游标";
            case 2 -> "≡ 接上前段";
            case 3 -> "在游标处切开";
            default -> count <= 1 ? "重置通道" : "并入上一段";
        };
    }

    private void drawFieldRow(DrawContext context, Rect field, String label, String fieldKey,
                              EventState.Event event) {
        context.drawText(textRenderer, Text.literal(label), panelX + PADDING, field.y0() + 4, LABEL_COLOR, false);
        if (editorKey != null && editorKey.equals(editorKeyFor(selected, fieldKey))) {
            return;
        }
        context.fill(field.x0(), field.y0(), field.x1(), field.y1(), 0xFF000000);
        context.drawText(textRenderer, Text.literal(fieldText(fieldKey, event)), field.x0() + 4, field.y0() + 4,
                VALUE_COLOR, false);
    }

    private void renderCreateArea(DrawContext context, Layout layout, EventState.Snapshot state) {
        context.drawText(textRenderer,
                Text.literal(String.format(Locale.ROOT,
                        "在游标处切开时间线（游标 %s 拍；切开不改变曲线，之后再改值）", fmt(cursorBeat()))),
                panelX + PADDING, layout.createLabelY(), LABEL_COLOR, false);
        Rect create = layout.createButton();
        context.fill(create.x0(), create.y0(), create.x1(), create.y1(), BUTTON_BG_ACTIVE);
        centered(context, "＋ 在游标处切开", create, VALUE_COLOR);
    }

    /** 通道预览开关（在播放按钮下面一行）：无音乐、只应用当前通道。 */
    private void renderPreviewToggle(DrawContext context, Layout layout, EventState.Snapshot state) {
        Rect toggle = layout.previewButton();
        boolean on = state.preview();
        context.fill(toggle.x0(), toggle.y0(), toggle.x1(), toggle.y1(), on ? BUTTON_BG_ACTIVE : BUTTON_BG);
        centered(context, on ? "只预览当前通道：开（无音乐）" : "只预览当前通道：关（无音乐）", toggle, VALUE_COLOR);
    }

    private void renderFooter(DrawContext context, Layout layout, EventState.Snapshot state) {
        for (int i = 0; i < layout.footerButtons().length; i++) {
            Rect rect = layout.footerButtons()[i];
            boolean enabled = i != 0 || selectedEvent(state) != null;
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), enabled ? BUTTON_BG : BUTTON_BG_DISABLED);
            centered(context, FOOTER_LABELS[i], rect, enabled ? VALUE_COLOR : HINT_COLOR);
        }
        Rect close = layout.closeButton();
        context.fill(close.x0(), close.y0(), close.x1(), close.y1(), BUTTON_BG);
        centered(context, "关闭", close, VALUE_COLOR);
    }

    private static final String[] FOOTER_LABELS = {"试听本段", "撤销", "重做"};

    private void renderEasingPage(DrawContext context, Layout layout, EventState.Snapshot state) {
        int rowH = 15;
        int perColumn = Math.max(1, (panelH - HEADER_H - 26) / rowH);
        context.drawText(textRenderer, Text.literal("缓动曲线（34 种，滚轮翻页）"),
                panelX + PADDING, layout.titleY() + scrollOffset, HEADER_COLOR, false);
        context.drawText(textRenderer, Text.literal("IN 先慢后快 · OUT 先快后慢 · IN_OUT 两头慢"),
                panelX + PADDING, layout.titleY() + scrollOffset + LINE_H, DESC_COLOR, false);
        EventState.Event event = selectedEvent(state);
        int columnW = (panelW - PADDING * 2) / 2;
        for (int i = easingBase; i < Easing.count() && i < easingBase + perColumn * 2; i++) {
            int slot = i - easingBase;
            Rect rect = easingCell(rowH, columnW, slot % perColumn, slot / perColumn);
            boolean active = event != null && event.easing() == i;
            context.fill(rect.x0(), rect.y0(), rect.x1(), rect.y1(), active ? BUTTON_BG_ACTIVE : BUTTON_BG);
            context.drawText(textRenderer, Text.literal(easingChinese(i) + "  " + Easing.name(i)),
                    rect.x0() + 3, rect.y0() + 4, VALUE_COLOR, false);
        }
    }

    private void drawScrollbar(DrawContext context) {
        if (scrollMax <= 0) {
            return;
        }
        int trackTop = panelY + HEADER_H;
        int trackBottom = panelY + panelH - 1;
        context.fill(panelX + panelW - 4, trackTop, panelX + panelW - 1, trackBottom, SCROLL_TRACK);
        Rect thumb = scrollThumbRect();
        if (thumb != null) {
            context.fill(thumb.x0(), thumb.y0(), thumb.x1(), thumb.y1(), SCROLL_THUMB);
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

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
        EventState.Snapshot state = CharterAudioClient.get().eventState().snapshot();
        Layout layout = layoutFor(state);
        if (headerRect().contains(mx, my)) {
            commitEditor();
            panelDragging = true;
            dragOffsetX = (int) (mx - panelX);
            dragOffsetY = (int) (my - panelY);
            return true;
        }
        Rect thumb = scrollThumbRect();
        if (thumb != null && thumb.contains(mx, my)) {
            commitEditor();
            scrollDragging = true;
            return true;
        }
        if (easingPage) {
            pickEasing(state, layout, mx, my);
            return true;
        }
        for (int i = 0; i < EventState.CHANNEL_COUNT; i++) {
            if (layout.channels()[i].contains(mx, my)) {
                commitEditor();
                selected = -1;
                scrollOffset = 0;
                easingBase = 0;
                CharterAudioClient.get().requestEventList(i);
                return true;
            }
        }
        for (int i = 0; i < layout.listCount() && i < state.events().size(); i++) {
            if (layout.listRows()[i].contains(mx, my)) {
                commitEditor();
                // 再点一次同一行 = 收起
                selected = selected == i ? -1 : i;
                if (selected >= 0) {
                    scrollCardIntoView(layout);
                }
                return true;
            }
        }
        if (selectedEvent(state) != null && clickCard(layout, state, mx, my)) {
            return true;
        }
        if (layout.createButton().contains(mx, my)) {
            commitEditor();
            CharterAudioClient.get().requestEventSplit(state.channel(), cursorBeat());
            return true;
        }
        if (layout.previewButton().contains(mx, my)) {
            commitEditor();
            CharterAudioClient.get().requestEventPreview(state.channel(), !state.preview());
            return true;
        }
        for (int i = 0; i < layout.footerButtons().length; i++) {
            if (layout.footerButtons()[i].contains(mx, my)) {
                clickFooter(i, state);
                return true;
            }
        }
        if (layout.closeButton().contains(mx, my)) {
            close();
            return true;
        }
        if (!insidePanel(mx, my)) {
            commitEditor();
        }
        return super.mouseClicked(click, doubled);
    }

    private boolean clickCard(Layout layout, EventState.Snapshot state, double mx, double my) {
        EventState.Event event = selectedEvent(state);
        if (event == null) {
            return false;
        }
        String[] keys = {"start", "end", "a", "b"};
        for (int i = 0; i < keys.length; i++) {
            if (layout.cardField(i).contains(mx, my)) {
                commitEditor();
                openEditor(editorKeyFor(selected, keys[i]), "数值",
                        fieldText(keys[i], event), layout.cardField(i));
                return true;
            }
        }
        if (layout.cardEasing().contains(mx, my)) {
            commitEditor();
            easingPage = true;
            easingBase = 0;
            return true;
        }
        for (int i = 0; i < layout.cardButtons().length; i++) {
            if (layout.cardButtons()[i].contains(mx, my)) {
                clickCardButton(i, state, event);
                return true;
            }
        }
        for (int i = 0; i < layout.stepButtons().length; i++) {
            if (layout.stepButtons()[i].contains(mx, my)) {
                stepSelection(state, i == 0 ? -1 : 1);
                scrollCardIntoView(layout);
                return true;
            }
        }
        return false;
    }

    private void clickCardButton(int index, EventState.Snapshot state, EventState.Event event) {
        CharterAudioClient audio = CharterAudioClient.get();
        switch (index) {
            case 0 -> sendUpdate(state.channel(), selected, event.withStart(cursorBeat()),
                    CharterAudioChannel.EVENT_VALUE_BOTH);
            case 1 -> sendUpdate(state.channel(), selected, event.withEnd(cursorBeat()),
                    CharterAudioChannel.EVENT_VALUE_BOTH);
            case 2 -> {
                // 把本段起点值接到前一段终点值 → 消除该边界的跳变
                if (selected <= 0) {
                    return;
                }
                double previousEnd = state.events().get(selected - 1).endValue();
                sendUpdate(state.channel(), selected, event.withStartValue(previousEnd),
                        CharterAudioChannel.EVENT_VALUE_START);
            }
            case 3 -> audio.requestEventSplit(state.channel(), cursorBeat());
            default -> {
                if (state.events().size() <= 1) {
                    selected = -1;
                    audio.requestEventClear(state.channel());
                } else {
                    int target = selectedOf(state);
                    selected = -1;
                    audio.requestEventRemove(state.channel(), target);
                }
            }
        }
    }

    private int selectedOf(EventState.Snapshot state) {
        return Math.max(0, Math.min(selected, state.events().size() - 1));
    }

    private void clickFooter(int index, EventState.Snapshot state) {
        CharterAudioClient audio = CharterAudioClient.get();
        switch (index) {
            case 0 -> {
                if (selectedEvent(state) != null) {
                    commitEditor();
                    audio.requestEventAudition(state.channel(), selected);
                }
            }
            case 1 -> audio.requestEdit(CharterAudioChannel.EDIT_UNDO);
            default -> audio.requestEdit(CharterAudioChannel.EDIT_REDO);
        }
    }

    private void stepSelection(EventState.Snapshot state, int delta) {
        if (state.events().isEmpty()) {
            return;
        }
        selected = Math.max(0, Math.min(state.events().size() - 1, selected + delta));
    }

    /** 选中某段后把展开的卡片滚进视野。 */
    private void scrollCardIntoView(Layout layout) {
        if (scrollMax <= 0) {
            return;
        }
        int viewTop = panelY + HEADER_H;
        int cardTop = layout.cardTop();
        int cardBottom = cardTop + CARD_MIN_EXTRA;
        if (cardTop < viewTop + 4) {
            scrollOffset = clamp(scrollOffset - (viewTop + 4 - cardTop), 0, scrollMax);
        } else if (cardBottom > panelY + panelH - 4) {
            scrollOffset = clamp(scrollOffset + (cardBottom - (panelY + panelH - 4)), 0, scrollMax);
        }
    }

    private void pickEasing(EventState.Snapshot state, Layout layout, double mx, double my) {
        int rowH = 15;
        int perColumn = Math.max(1, (panelH - HEADER_H - 26) / rowH);
        int columnW = (panelW - PADDING * 2) / 2;
        EventState.Event event = selectedEvent(state);
        if (event == null) {
            easingPage = false;
            return;
        }
        for (int i = easingBase; i < Easing.count() && i < easingBase + perColumn * 2; i++) {
            int slot = i - easingBase;
            if (easingCell(rowH, columnW, slot % perColumn, slot / perColumn).contains(mx, my)) {
                sendUpdate(state.channel(), selected, event.withEasing(i));
                easingPage = false;
                return;
            }
        }
        easingPage = false;
    }

    private Rect easingCell(int rowH, int columnW, int row, int column) {
        int x = panelX + PADDING + column * columnW;
        int y = panelY + HEADER_H + row * rowH;
        return new Rect(x, y, x + columnW - 2, y + rowH - 2);
    }

    private void sendUpdate(int channel, int index, EventState.Event event) {
        sendUpdate(channel, index, event, CharterAudioChannel.EVENT_VALUE_BOTH);
    }

    private void sendUpdate(int channel, int index, EventState.Event event, int valueMode) {
        CharterAudioClient.get().requestEventUpdate(channel, index,
                event.startBeat(), event.endBeat(), event.startValue(), event.endValue(), event.easing(),
                valueMode);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (panelDragging) {
            panelX = clampX((int) (click.x() - dragOffsetX));
            panelY = clampY((int) (click.y() - dragOffsetY));
            return true;
        }
        if (scrollDragging) {
            dragScrollbar(click.y());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    private void dragScrollbar(double mouseY) {
        Rect thumb = scrollThumbRect();
        if (thumb == null) {
            return;
        }
        int trackTop = panelY + HEADER_H;
        int trackBottom = panelY + panelH - 1;
        int thumbH = thumb.y1() - thumb.y0();
        int travel = Math.max(1, trackBottom - trackTop - thumbH);
        double ratio = (mouseY - trackTop - thumbH / 2.0) / travel;
        scrollOffset = clamp((int) Math.round(ratio * scrollMax), 0, scrollMax);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (panelDragging) {
            panelDragging = false;
            EditorUiConfig.saveEventPosition(panelX, panelY);
            return true;
        }
        if (scrollDragging) {
            scrollDragging = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (easingPage) {
            int rowH = 15;
            int perColumn = Math.max(1, (panelH - HEADER_H - 26) / rowH);
            int base = easingBase + (vertical > 0 ? -perColumn : perColumn);
            easingBase = Math.max(0, Math.min(Math.max(0, Easing.count() - perColumn * 2), base));
            return true;
        }
        if (!insidePanel(mouseX, mouseY)) {
            return false;
        }
        scrollOffset = clamp(scrollOffset + (vertical > 0 ? -SCROLL_STEP : SCROLL_STEP), 0, scrollMax);
        return true;
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
            if (easingPage) {
                easingPage = false;
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
        CharterAudioClient.get().requestEventClose(CharterAudioClient.get().eventState().snapshot().channel());
        super.close();
    }

    /** 是否有就地输入框处于聚焦（EditInput 据此让出 Ctrl+Z/Y）。 */
    public boolean isTextEditing() {
        return editor != null;
    }

    // ------------------------------------------------------------------
    // 就地输入
    // ------------------------------------------------------------------

    private void openEditor(String key, String label, String text, Rect rect) {
        commitEditor();
        editorKey = key;
        TextFieldWidget widget = new TextFieldWidget(textRenderer, rect.x0(), rect.y0() + 1,
                Math.max(48, rect.width()), FIELD_H - 2, Text.literal(label));
        widget.setMaxLength(14);
        widget.setDrawsBackground(false);
        widget.setEditableColor(VALUE_EDIT_COLOR);
        widget.setText(text);
        widget.setTextPredicate(value -> value.isEmpty() || value.matches("-?\\d*\\.?\\d*"));
        widget.setFocused(true);
        widget.setCursorToEnd(false);
        editor = widget;
        addDrawableChild(widget);
    }

    private void commitEditor() {
        if (editor == null) {
            return;
        }
        String key = editorKey;
        String text = editor.getText() == null ? "" : editor.getText().trim();
        remove(editor);
        editor = null;
        editorKey = null;
        if (key == null || text.isEmpty()) {
            return;
        }
        double value;
        // 流速是倍率：允许 1.25x / 0.5X 写法
        String numeric = text.endsWith("x") || text.endsWith("X")
                ? text.substring(0, text.length() - 1).trim() : text;
        try {
            value = Double.parseDouble(numeric);
        } catch (NumberFormatException e) {
            return;
        }
        if (!Double.isFinite(value)) {
            return;
        }
        EventState.Snapshot state = CharterAudioClient.get().eventState().snapshot();
        int split = key.indexOf(':');
        if (split <= 0) {
            return;
        }
        int index;
        try {
            index = Integer.parseInt(key.substring(0, split));
        } catch (NumberFormatException e) {
            return;
        }
        EventState.Event event = state.event(index);
        if (event == null) {
            return;
        }
        String field = key.substring(split + 1);
        EventState.Event updated = switch (field) {
            case "start" -> event.withStart(value);
            case "end" -> event.withEnd(value);
            case "a" -> event.withStartValue(value);
            default -> event.withEndValue(value);
        };
        // 起值独立（可造跳变）；止值默认同步下一段起点值（保持连续）
        int valueMode = switch (field) {
            case "a" -> CharterAudioChannel.EVENT_VALUE_START;
            case "b" -> CharterAudioChannel.EVENT_VALUE_END;
            default -> CharterAudioChannel.EVENT_VALUE_BOTH;
        };
        sendUpdate(state.channel(), index, updated, valueMode);
    }

    private void cancelEditor() {
        if (editor == null) {
            return;
        }
        remove(editor);
        editor = null;
        editorKey = null;
    }

    // ------------------------------------------------------------------
    // 布局 / 滚动
    // ------------------------------------------------------------------

    private record Layout(int contentBottom,
                          Rect[] channels, Rect[] listRows, int listCount,
                          int titleY, int infoY, int descY, int listHeaderY,
                          int cardTop, int cardBottom, int cardTitleY, int cardEffectY,
                          Rect[] cardFields, Rect cardEasing, Rect[] cardButtons, Rect[] stepButtons,
                          int createLabelY, Rect createButton, Rect previewButton,
                          Rect[] footerButtons, Rect closeButton, int hintY) {

        Rect cardField(int index) {
            return cardFields[index];
        }
    }

    /** 计算布局 + 更新 panelH / contentH / scrollOffset / scrollMax（绘制与命中的统一入口）。 */
    private Layout layoutFor(EventState.Snapshot state) {
        int rows = Math.max(1, Math.min(MAX_LIST_ROWS, state.events().size()));
        boolean hasCard = selectedEvent(state) != null;
        Layout layout = computeLayout(state, rows, hasCard, panelY - scrollOffset);
        contentH = Math.max(HEADER_H, layout.contentBottom() - panelY);
        int available = Math.max(140, this.height - MARGIN * 2);
        panelH = Math.min(contentH, available);
        scrollMax = Math.max(0, contentH - panelH);
        scrollOffset = clamp(scrollOffset, 0, scrollMax);
        return layout;
    }

    private Layout computeLayout(EventState.Snapshot state, int rows, boolean hasCard, int originY) {
        int x = panelX;
        int y = originY + 6;
        int titleY = y;
        y += LINE_H + 2;
        int infoY = y;
        y += LINE_H + 4;
        int channelTop = y;
        Rect[] channels = new Rect[EventState.CHANNEL_COUNT];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = channelRect(i, channelTop);
        }
        y = channelTop + 2 * (CH_H + 2) + 2;
        int descY = y;
        y += LINE_H * 2 + 4;
        int listHeaderY = y;
        y += LINE_H;
        Rect[] listRows = new Rect[rows];
        for (int i = 0; i < rows; i++) {
            listRows[i] = new Rect(x + PADDING - 2, y + i * ROW_H, x + panelW - PADDING + 2, y + (i + 1) * ROW_H - 2);
        }
        y += rows * ROW_H + 4;
        int cardTop = y;
        Rect[] cardFields = new Rect[0];
        Rect[] cardButtons = new Rect[0];
        Rect[] stepButtons = new Rect[0];
        Rect cardEasing = null;
        int cardTitleY = y;
        int effectY = y;
        int cardBottom = y + LINE_H + 6;
        if (hasCard) {
            cardTitleY = y + 4;
            int fieldStart = cardTitleY + LINE_H + 2;
            cardFields = new Rect[4];
            for (int i = 0; i < 4; i++) {
                cardFields[i] = new Rect(cellX(LABEL_W), fieldStart + i * (FIELD_H + 2),
                        cellX(LABEL_W + FIELD_W), fieldStart + i * (FIELD_H + 2) + FIELD_H);
            }
            int easingY = fieldStart + 4 * (FIELD_H + 2) + 2;
            cardEasing = new Rect(cellX(LABEL_W), easingY, cellX(panelW - PADDING * 2 - 8), easingY + FIELD_H);
            effectY = easingY + FIELD_H + 6;
            int buttonY = effectY + LINE_H * 2 + 4;
            cardButtons = new Rect[5];
            for (int i = 0; i < 5; i++) {
                cardButtons[i] = footerLike(x, panelW, 5, i, buttonY);
            }
            stepButtons = new Rect[2];
            int stepY = buttonY + BUTTON_H + 2;
            stepButtons[0] = new Rect(x + PADDING, stepY, x + PADDING + 90, stepY + BUTTON_H - 2);
            stepButtons[1] = new Rect(x + panelW - PADDING - 90, stepY, x + panelW - PADDING,
                    stepY + BUTTON_H - 2);
            cardBottom = stepY + BUTTON_H + 2;
        }
        int createLabelY = cardBottom + 6;
        Rect createButton = new Rect(x + PADDING, createLabelY + LINE_H,
                x + panelW - PADDING, createLabelY + LINE_H + FIELD_H);
        int footerY = createLabelY + LINE_H + FIELD_H + 4;
        Rect[] footerButtons = new Rect[3];
        for (int i = 0; i < 3; i++) {
            footerButtons[i] = footerLike(x, panelW, 3, i, footerY);
        }
        // 通道预览开关放在播放按钮下面（先「试听/撤销/重做」，再预览开关，最后关闭）
        int previewY = footerY + BUTTON_H + 2;
        Rect previewButton = new Rect(x + PADDING, previewY, x + panelW - PADDING, previewY + FIELD_H);
        Rect closeButton = new Rect(x + PADDING, previewY + FIELD_H + 4, x + panelW - PADDING,
                previewY + FIELD_H + 4 + BUTTON_H);
        int hintY = previewY + FIELD_H + 4 + BUTTON_H + 4;
        return new Layout(hintY + LINE_H + 10, channels, listRows, rows, titleY, infoY, descY, listHeaderY,
                cardTop, cardBottom, cardTitleY, effectY, cardFields, cardEasing, cardButtons, stepButtons,
                createLabelY, createButton, previewButton, footerButtons, closeButton, hintY);
    }

    private Rect footerLike(int panelX0, int width, int count, int index, int y) {
        int gap = 4;
        int total = width - PADDING * 2;
        int w = (total - gap * (count - 1)) / count;
        int x = panelX0 + PADDING + index * (w + gap);
        return new Rect(x, y, x + w, y + BUTTON_H - 2);
    }

    private Rect channelRect(int index, int channelTop) {
        int gap = 3;
        int row = index / CH_PER_ROW;
        int column = index % CH_PER_ROW;
        int total = panelW - PADDING * 2;
        int w = (total - gap * (CH_PER_ROW - 1)) / CH_PER_ROW;
        int x = panelX + PADDING + column * (w + gap);
        int rowY = channelTop + row * (CH_H + 2);
        return new Rect(x, rowY, x + w, rowY + CH_H - 2);
    }

    private Rect scrollThumbRect() {
        if (scrollMax <= 0 || contentH <= 0) {
            return null;
        }
        int trackTop = panelY + HEADER_H;
        int trackBottom = panelY + panelH - 1;
        int travel = Math.max(1, trackBottom - trackTop);
        int height = Math.max(24, (int) ((long) travel * panelH / contentH));
        height = Math.min(height, travel);
        int y = trackTop + (int) Math.round((double) (travel - height) * scrollOffset / scrollMax);
        return new Rect(panelX + panelW - 4, y, panelX + panelW - 1, y + height);
    }

    private int cellX(int offset) {
        return panelX + PADDING + offset;
    }

    private Rect headerRect() {
        return new Rect(panelX, panelY, panelX + panelW, panelY + HEADER_H - 6);
    }

    private void drawFrame(DrawContext context, Layout layout) {
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG);
        context.fill(panelX, panelY, panelX + panelW, panelY + 1, BORDER);
        context.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, BORDER);
        context.fill(panelX, panelY, panelX + 1, panelY + panelH, BORDER);
        context.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, BORDER);
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void clampSelection(EventState.Snapshot state) {
        if (selected >= state.events().size()) {
            selected = state.events().size() - 1;
        }
    }

    private EventState.Event selectedEvent(EventState.Snapshot state) {
        return selected >= 0 && selected < state.events().size() ? state.events().get(selected) : null;
    }

    private static int channel(EventState.Snapshot state) {
        int channel = state.channel();
        return channel >= 0 && channel < EventState.CHANNEL_COUNT ? channel : 0;
    }

    /** 通道在当前游标处的值。 */
    private String channelValueText(EventState.Snapshot state) {
        EventState.Event last = null;
        for (EventState.Event event : state.events()) {
            if (event.startBeat() <= cursorBeat() + 1.0E-6) {
                last = event;
            }
        }
        if (last == null) {
            return "默认值";
        }
        return fmt(last.endValue()) + " " + CH_UNITS[channel(state)];
    }

    private double cursorBeat() {
        CharterAudioClient audio = CharterAudioClient.get();
        double ms = audio.engine().isPlaying() ? audio.engine().positionMs() : audio.viewState().cursorMs();
        TimelineGrid grid = new TimelineGrid(audio.chartMeta());
        return grid.isUsable() ? Math.max(0d, grid.beatAtMs(Math.max(0d, ms))) : 0d;
    }

    // ------------------------------------------------------------------
    // 文案
    // ------------------------------------------------------------------

    private String effectSentence(EventState.Snapshot state, EventState.Event event) {
        String subject = CH_SUBJECTS[channel(state)];
        String unit = CH_UNITS[channel(state)];
        boolean jumpAfter = selected + 1 < state.events().size()
                && Math.abs(event.endValue() - state.events().get(selected + 1).startValue()) > 1.0E-6;
        String boundary = jumpAfter ? "　⟂ 此段终点与下一段起点不连续（跳变）" : "";
        if (Math.abs(event.endValue() - event.startValue()) < 1.0E-9) {
            return String.format(Locale.ROOT, "第 %s → %s 拍，%s 恒为 %s %s。%s",
                    fmt(event.startBeat()), fmt(event.endBeat()), subject, fmt(event.startValue()), unit, boundary);
        }
        return String.format(Locale.ROOT, "第 %s → %s 拍，%s 由 %s %s 过渡至 %s %s。%s",
                fmt(event.startBeat()), fmt(event.endBeat()), subject,
                fmt(event.startValue()), unit, fmt(event.endValue()), unit, boundary);
    }

    private static String fieldText(String field, EventState.Event event) {
        return switch (field) {
            case "start" -> fmt(event.startBeat());
            case "end" -> fmt(event.endBeat());
            case "a" -> fmt(event.startValue());
            default -> fmt(event.endValue());
        };
    }

    /** 最后一段的终点是曲尾时补一句说明。 */
    private static String sentinelNote(EventState.Snapshot state, EventState.Event event) {
        boolean last = state.events().indexOf(event) == state.events().size() - 1;
        return last ? "（曲尾）" : "";
    }

    private static String editorKeyFor(int index, String field) {
        return index + ":" + field;
    }

    /** 数值显示：大数取整（1000000），其余保留 3 位小数。 */
    private static String fmt(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (Math.abs(value) >= 10000d) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** 缓动中文名：只对最常用的几类给中文，其余保留英文名。 */
    private static String easingChinese(int id) {
        String name = Easing.name(id);
        if (name.equals("LINEAR")) {
            return "匀速";
        }
        if (name.startsWith("IN_OUT_")) {
            return "两头慢";
        }
        if (name.startsWith("IN_")) {
            return "先慢后快";
        }
        if (name.startsWith("OUT_")) {
            return "先快后慢";
        }
        return name;
    }

    private void centered(DrawContext context, String text, Rect rect, int color) {
        int width = textRenderer.getWidth(text);
        context.drawText(textRenderer, Text.literal(text),
                (rect.x0() + rect.x1() - width) / 2, rect.y0() + 4, color, false);
    }
}
