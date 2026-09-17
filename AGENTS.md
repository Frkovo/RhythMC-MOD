# AGENTS.md

RhythMC-MOD is the Fabric client mod (Minecraft 1.21.11, yarn mappings, Java 21) for the
RhythMC charter editor. It is the client half of the `rhythmc:charter_audio` plugin channel,
talking to the Paper plugin **RhythMC-Charter-V2** (parent repository).

**Together with the plugin, this mod is the project's only chart editor.** The sibling tools
`RhythMCChartMaker` / `RhythMC-Preview` are legacy tooling and a preview service — never
document or extend them as chartering entry points.

## Read First

1. `AGENTS.md` (this file)
2. `.agent/CONTRACTS.md` — authoritative `rhythmc:charter_audio` contract
3. `../.agent/RMCD-FORMAT.md` — chart persistence format (parent repo; RMCD v1)
4. `../src/main/resources/config.yml` — server-side audio push settings (parent repo)

## Repository Map

| Area | Path | Responsibility |
|---|---|---|
| This repo | `E:/Dev/RhythMC-Charter-V2/RhythMC-MOD` | Fabric client mod: editor UI (event/note panels, timeline HUD), chunked download receiver, audio decode/playback |
| Server plugin | `E:/Dev/RhythMC-Charter-V2` | Paper plugin: the **only chart editor** — in-game editor sessions, `/charter` commands, handshake, audio push service |
| Sibling repos | `E:/Dev/RhythMCChartMaker`, `E:/Dev/RhythMC-Preview` | **Not charter editors** (legacy tooling / preview service); own the separate `rhythmc:chart_preview` channel |
| Contract | `.agent/CONTRACTS.md` | Opcode table, payload encoding, lifecycle, trust model |
| Mixin config | `src/client/resources/rhythmc-mod.client.mixins.json` | Client mixin registration |

## Prime Directive

The wire protocol is a **cross-repo contract**. A contract change is any change to:

- opcodes, frame layout, payload fields, chunking, lifecycle or trust assumptions
- handshake, capabilities or session semantics

Contract changes must update, **in the same task**:

1. this mod (implementation),
2. the server plugin in the parent repo (implementation),
3. `.agent/CONTRACTS.md` in **both** repositories (keep them identical),
4. `../.agent/RMCD-FORMAT.md` when the chart persistence format changes.

## Non-Negotiable Rules

### Safety

- Never `git revert`, `git rebase`, `git reset --hard` or `git push --force`.
- Never commit or push unless the user explicitly asks. Never stage unrelated dirty files.
- Never commit credentials, tokens or private keys.

### Scope Discipline

- No gameplay features, HTTP/WebSocket servers, auth systems or resource-pack distribution
  without an explicit user decision.
- Do not add compatibility layers for old servers; the protocol baseline is v1.

### Trust

- The server only pushes audio after the mod sent `HELLO` and received `HELLO_ACK.ok = true`.

## Plugin Channel Contract (`rhythmc:charter_audio`, PROTOCOL_VERSION=1)

Frame = `[big-endian int opcode][string sessionId][payload]`.
String = `int byteLength + UTF-8 bytes`. No `writeUTF`, no varints.
Unknown opcodes are ignored. `sessionId` is always `""` and is not validated.

| Direction | Opcode | Name | Payload |
|---|---|---|---|
| C→S | 1 | `HELLO` | `int protocolVersion, String modVersion, int capabilities` |
| C→S | 10 | `TRANSPORT_REQ` | `byte action, int bars`（键位请求，全部交插件执行；0 播放暂停 1 上一小节 2 下一小节 3 A 4 B 5 清循环 6 停止） |
| C→S | 11 | `AUDIO_PUSH_ACK` | `String transferId, byte ok, String reason` |
| C→S | 103 | `STATE` | `byte playing, double positionMs, float speed, double lengthMs`（每 5 tick + 状态变更即发；lengthMs=音频时长，驱动曲尾自动停止） |
| C→S | 105 | `ERROR` | `String message` |
| S→C | 2 | `CHART_META` | `String songName, long lengthMs, long offsetMs, int bpmCount, {double beat,double bpm}[], int subdivCount, {double startBeat,int noteValue}[]`（当前 Track 分音网格；mod 时间轴 HUD 按它画线） |
| S→C | 3 | `TRANSPORT_PLAY` | `double fromMs, float speed` |
| S→C | 4 | `TRANSPORT_PAUSE` | — |
| S→C | 5 | `TRANSPORT_SEEK` | `double toMs` |
| S→C | 6 | `TRANSPORT_STOP` | — |
| S→C | 7 | `SET_LOOP` | `double aMs, double bMs`（bMs<0 清除） |
| S→C | 101 | `HELLO_ACK` | `boolean ok, String serverVersion, String sessionId` |
| S→C | 106 | `AUDIO_PUSH_START` | `String transferId, String songFolder, String fileName, long totalBytes, String sha256, int chunkSize, int totalChunks` |
| S→C | 107 | `AUDIO_PUSH_CHUNK` | `String transferId, int index, int length, byte[length]` |
| S→C | 108 | `AUDIO_PUSH_END` | `String transferId` |
| S→C | 109 | `CHART_NOTES` | `int count, {double beat, byte type}[]`（时间轴 HUD 音符快照；mod-ready/播放/音符变更时全量发送，type=NoteType ordinal） |
| C→S | 110 | `VIEW_ZOOM` | `byte direction`（世界网格缩放 ±1 级；SHIFT+滚轮） |
| S→C | 111 | `VIEW_STATE` | `int zoomIndex, double barBlocks, int levelCount, double cursorMs`（时间轴 HUD 窗口中心/缩放状态） |
| C→S | 112 | `VIEW_SEEK` | `double toMs`（ALT 调整层点击/拖动时间轴 seek） |
| C→S | 113 | `EDIT_REQ` | `byte action` [+ APPLY 载荷：`byte type, double beat, posX, posY, posZ, float scaleX, scaleY, scaleZ, rotX, rotY, rotZ, byte holdBoundary, int holdGroupManual`]（编辑器请求：0 打开属性面板/1 撤销/2 重做/3 取消选中/4 应用属性/5 删除选中/6 复制到下一分音点；中键发 0，Ctrl+Z/Y 发 1/2） |
| S→C | 114 | `EDIT_STATE` | `boolean ok, String reason, byte type, double beat, posX, posY, posZ, float scaleX, scaleY, scaleZ, rotX, rotY, rotZ, int holdGroup, holdGroupSize, holdGroupIndex, byte holdBoundary, int holdGroupManual, double maxHalfWidth, maxHalfHeight, beatStep, boolean canUndo, canRedo`（选中音符属性快照；ok=true 自动打开属性面板，ok=false 关闭；holdBoundary 0 自动/1 链首/2 链尾；holdGroupManual -1 自动/≥0 显式组号） |
| C→S | 115 | `EVENT_REQ` | `byte op` [+ 载荷]（Track 事件：0 列表/打开面板 `byte channel`、1 切开 `byte channel, double beat`（把该拍所在段一分为二，曲线形状不变，段数 +1）、2 修改 `byte channel, int index, double startBeat, endBeat, startValue, endValue, int easing, byte valueMode`（拍=移动与邻段共享的边界；valueMode 0 起止值都设 / 1 只设终点值并同步下一段起点值 / 2 只设起点值＝造跳变）、3 并入上一段 `byte channel, int index`（段数 −1，只剩 1 段时拒绝）、4 重置通道 `byte channel`、5 关闭面板 `byte channel`、6 试听 `byte channel, int index`（index<0 = 从游标播放）；channel 0 Speed/1 X/2 Y/3 Z/4 SX/5 SY/6 SZ/7 RX/8 RY/9 RZ；**通道是 `[0, 曲尾]` 的连续分段（无缝隙无重叠，每段 ≥0.25 拍），跳变＝公共边界上值不连续（不需要零长度段）**；曲尾 = max(曲长换算拍数, 末音符拍 + 8)；值域 speed ±640 格/秒 / 位移 ±256 / 缩放 0.01–16 / 旋转 ±720） |
| S→C | 116 | `EVENT_STATE` | `boolean ok, String reason, int trackId, byte channel, byte panel, int selectIndex, int count, {double startBeat, double endBeat, double startValue, double endValue, int easing}[]`（某 Track 某通道全部事件段；panel=1 仅 LIST 应答时下发；ok=false 关面板） |
| S→C | 117 | `EVENT_HUD` | `int trackId, byte channel, int count, {double startBeat, double endBeat, double startValue, double endValue, byte easing}[]`（时间轴 HUD 的当前通道曲线；mod-ready/切 Track/切通道/事件增删改/undo-redo/关会话时推送，不塞进每 tick 的 VIEW_STATE） |
| C→S | 118 | `BOUNDS_REQ` | `byte on`（mod 键位 `B`：轨道轮廓可视化开关；**只在 glob 实地播放中生效**，否则插件只回一句提示。插件本身不渲染任何东西，只回 119） |
| S→C | 119 | `BOUNDS_DATA` | `boolean on, double baseX, baseY, baseZ, planeHalf, boxLength, yOffset, int trackCount, {int trackId, 10× (int segCount, {double startBeat, endBeat, startValue, endValue, byte easing}[])}[]`（**静态**轮廓数据：判定面基点 + 判定面半宽/盒长/垂直偏移 + 每条 Track 的 10 条事件通道（顺序 = EventChannel：0 Speed/1 X/2 Y/3 Z/4 SX/5 SY/6 SZ/7 RX/8 RY/9 RZ）。开关打开/谱面变化时下发，**不每 tick**；mod 端按自己的音频时钟换算拍、本地求值并用 vanilla gizmo 画：12 棱框架（可穿墙）+ 半透明红判定面 + `TRACK #N`（**侧面不填充**）；`on=false` 时 trackCount=0） |

Reserved for later milestones (do not repurpose): 8, 9, 102, 104.
See `.agent/CONTRACTS.md` for the full lifecycle and trust model.

## Source Layout

| Path | Responsibility |
|---|---|
| `src/main/java/.../rhythmcMod/RhythmcMod.java` | Common mod entry point |
| `src/client/java/.../client/RhythmcModClient.java` | Client entry point, registers the audio client |
| `src/client/java/.../client/net/CharterAudioChannel.java` | Channel id, opcodes, protocol version (must match the plugin) |
| `src/client/java/.../client/net/CharterAudioPayload.java` | `CustomPayload` wrapper for raw frame bytes |
| `src/client/java/.../client/net/CharterAudioClient.java` | HELLO handshake, frame dispatch, transport handling, STATE reporting |
| `src/client/java/.../client/net/ChartMetaState.java` | `CHART_META(2)` cache for the timeline HUD |
| `src/client/java/.../client/net/EditState.java` | `EDIT_STATE(114)` cache driving the note property panel |
| `src/client/java/.../client/net/EventState.java` | `EVENT_STATE(116)` cache (event segments) driving the event panel |
| `src/client/java/.../client/net/EventHudState.java` | `EVENT_HUD(117)` cache: current-channel curve for the timeline HUD |
| `src/client/java/.../client/net/EasingCurve.java` | Easing table (same 34 functions as the plugin EasingUtils) used to draw the HUD curve |
| `src/client/java/.../client/net/Easing.java` | 34 easing names (ordinal = wire value, mirrors the plugin `Easings`) |
| `src/client/java/.../client/edit/NoteEditScreen.java` | Note property panel (inline numeric editors + sliders, middle-click opened) |
| `src/client/java/.../client/edit/EventEditScreen.java` | Track event panel (channel row, segment list, inline editors, easing page, split/merge/reset, footer row `试听本段 / 撤销 / 重做`, `G` opened) |
| `src/client/java/.../client/input/EditInput.java` | Ctrl+Z / Ctrl+Y / Ctrl+Shift+Z + panel open/close sync |
| `src/client/java/.../client/input/CharterKeybinds.java` | Transport keybinds → `TRANSPORT_REQ(10)`; `G` → `EVENT_REQ LIST`; `B` → `BOUNDS_REQ(118)` track outline; `Ctrl+Space`（`KeyboardMixin` 里拦截）→ `TRANSPORT_REQ 0` 播放/暂停 |
| `src/client/java/.../client/net/BoundsDataState.java` | `BOUNDS_DATA(119)` cache: judge-plane base + every track's 10 event channels; `evaluate()` mirrors the plugin `ChartUtils.getTransformation` |
| `src/client/java/.../client/render/TrackBoundsRenderer.java` | Client-side track outline (vanilla 1.21.11 gizmo API): 5x5 judge plane + box (12 edges via `ignoreOcclusion()`, faint faces) + `TRACK #N`, evaluated per frame from the local beat |
| `src/client/java/.../client/audio/AudioTransferReceiver.java` | Chunked download: `.part` file, SHA-256 verify, atomic move |
| `src/client/java/.../client/audio/DownloadProgressState.java` | Shared progress state for the HUD |
| `src/client/java/.../client/audio/CharterAudioEngine.java` | PCM playback (SourceDataLine worker) |
| `src/client/java/.../client/audio/AudioStreamHelper.java` | Decoding to 16-bit PCM, including the FLAC path |
| `src/client/java/.../client/mixin/client/InGameHudMixin.java` | HUD progress bar overlay |

Downloaded audio goes to `<gameDir>/rhythmc-audio/<songFolder>/<fileName>`
(dev client: `run/rhythmc-audio/...`).

## Mod Rules

- Client-only code belongs in `src/client/java`; the mod is `environment: client`.
- Opcode constants in `CharterAudioChannel` must stay in sync with the plugin's
  `CharterAudioBridge`.
- Use `Text.literal(...)` for player-facing messages; do not hardcode raw strings.
- The mixin is registered in `rhythmc-mod.client.mixins.json` (`client` array).

### FLAC caveat (important)

`jflac-codec`'s SPI reader returns the raw FLAC bytes and `FlacFormatConversionProvider`
does not convert anything (`Flac2PcmAudioInputStream` copies bytes verbatim), so
`AudioSystem`/SPI decoding fails or produces garbage for FLAC — especially 24-bit.
`AudioStreamHelper.decodeFlacToPcm16()` therefore uses the low-level `org.jflac.FLACDecoder`
directly:

- `readMetadata()` → `getStreamInfo()`, then `addPCMProcessor(...)`, then **`decodeFrames()`**
  (never `decode()`, which re-reads metadata and throws `Could not find Stream Sync`).
- Decoder byte layout: 8-bit unsigned (+0x80), 16-bit LE, 24-bit LE — converted to 16-bit LE.

## Build and Run

```powershell
gradlew.bat build          # compile + tests + remap jar
gradlew.bat runClient      # dev client (game dir: ./run)
```

- Java 21. Loom handles remapping; audio deps are bundled via `include` in `build.gradle`
  (`vorbisspi`, `mp3spi`, `tritonus-share`, `jlayer`, `jflac-codec`).
- To test against the local server: start `ProdTestServer` (parent repo scripts), then join
  `localhost:25565`.

## Finish Checklist

- [ ] `gradlew.bat build` succeeds.
- [ ] Protocol change? Both repos updated + both `.agent/CONTRACTS.md` + design doc.
- [ ] Verified in-game against the plugin when possible (download, HUD, play/stop).
- [ ] Commit/push only if the user explicitly asked.
