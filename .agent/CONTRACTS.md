# Contract Map

Updated: 2026-09-15 (track events as a partition: SPLIT/UPDATE+valueMode/REMOVE-merge, jumps are boundary discontinuities, chart-end ranges)

## Contract Change Definition

A task crosses a contract boundary when it changes any of these:

- `rhythmc:charter_audio` plugin channel opcode set, direction, payload schema, chunking protocol, lifecycle, or trust model.
- Transport semantics (play/pause/seek/stop/loop/speed clocking) shared between the Paper plugin and the Fabric client mod.
- Audio push semantics (chunking, hashing, ACK, client storage layout).
- Plugin config keys or mod config keys that both sides depend on.

Any opcode/payload change must be made in both repos (plugin + mod) in the same task, with both `.agent/CONTRACTS.md` copies kept identical.

## The Only Contract: `rhythmc:charter_audio`

A bidirectional Bukkit plugin channel between the RhythMC-MOD client mod and this Paper plugin (RhythMC-Charter-V2). Opcodes and payload format must match `CharterAudioChannel.java` (mod) and `CharterAudioBridge.java` (plugin) on both sides.

Protocol version: `PROTOCOL_VERSION = 1` (fresh baseline; no legacy compatibility layers).

### Direction Client → Server

| Opcode | Name | Payload | Notes |
|---|---|---|---|
| 1 | `HELLO` | `int protocolVersion`, `String modVersion`, `int capabilities` | Sent 1s after JOIN. Retried every 100 ticks until handshake. `caps` bits: 1=SEEK, 2=LOOP, 4=SPEED. |
| 10 | `TRANSPORT_REQ` | `byte action`, `int bars` | Live (M2). Mod keybind request, always routed through the plugin (never handled locally). Actions: 0=toggle play/pause, 1=previous bar, 2=next bar, 3=set loop A, 4=set loop B, 5=clear loop, 6=stop. `bars` is the bar count for actions 1/2. |
| 11 | `AUDIO_PUSH_ACK` | `String transferId`, `byte ok`, `String reason` | Audio transfer verification result. `ok=0` carries a human-readable reason. |
| 103 | `STATE` | `byte playing`, `double positionMs`, `float speed`, `double lengthMs` | Live (M2). Sent every 5 client ticks while `HELLO_ACK.ok=true` and immediately after any transport op. Reconcile data source: the plugin clock is authoritative and corrects the mod. `lengthMs` is the decoded audio duration (0 when nothing is loaded) and drives the plugin's end-of-song stop. |
| 102 | `CHART_STATUS` | `boolean ok`, `String reason`, `String sha1`, `long lengthMs` | Reserved (V1). |
| 104 | `PONG` | `long nonce` | Reserved (V1); answers S→C `PING`. |
| 105 | `ERROR` | `String message` | Human-readable. Plugin logs it and forwards to the sender's chat. |
| 110 | `VIEW_ZOOM` | `byte direction` | Live (v1.1). Mod world-grid zoom request (SHIFT+wheel). `+1` = finer (more blocks per bar), `-1` = coarser; the plugin clamps to `editor.grid.zoom-levels`. |
| 112 | `VIEW_SEEK` | `double toMs` | Live (v1.1). Timeline click/drag seek from the ALT adjust overlay. The plugin runs its normal seek path (play/pause/edit); in edit mode it moves the cursor. |
| 113 | `EDIT_REQ` | `byte action` + per-action payload | Live (v1.2). Editor requests handled by the plugin session: `0 OPEN_NOTE_GUI` (pick the note under the crosshair, current track only, and open the property panel), `1 UNDO`, `2 REDO`, `3 DESELECT`, `4 APPLY` (`byte type`, `double beat`, `posX`, `posY`, `posZ`, `float scaleX`, `scaleY`, `scaleZ`, `rotX`, `rotY`, `rotZ`, `byte holdBoundary`, `int holdGroupManual`), `5 DELETE_SELECTED`, `6 CLONE_TO_NEXT`. `holdBoundary` is `0` auto / `1` chain head / `2` chain tail; `holdGroupManual` is `-1` auto or an explicit group id (`>= 0`) that wins over auto-chaining and may merge non-adjacent / cross-lane HOLD notes. APPLY is throttled client-side (at most one frame per client tick) and validated server-side (position clamped free-form, rotation ±180°, scale 0.1–3.0, `posZ` ±3, HOLD keeps `posY=-1` and rejects conversion unless the note already sits at `Y=-1`).
| 115 | `EVENT_REQ` | `byte op` + per-op payload | Live (v1.5). Track-event request for a single channel of the active track. `op`: `0 LIST` (`byte channel`; also means "open the event panel"), `1 SPLIT` (`byte channel`, `double beat`; **cut the segment containing `beat` in two** - the curve shape is unchanged, N segments become N+1), `2 UPDATE` (`byte channel`, `int index`, `double startBeat`, `endBeat`, `startValue`, `endValue`, `int easing`, `byte valueMode`; the beats move the **shared boundary** with the neighbouring segments, `valueMode`: `0` = set both values, `1` = set the end value and sync the next segment's start value (default continuity), `2` = set the start value only (independent, i.e. create/keep a jump)), `3 REMOVE` (`byte channel`, `int index`; **merge that segment into a neighbour**, segment count -1, rejected when only one segment is left), `4 CLEAR` (`byte channel`; reset to a single neutral segment), `5 PREVIEW` (`byte channel`, `byte on`; **isolated channel preview** - only the whole current channel is applied, the other nine channels stay neutral; the anchor is the per-track **observation point** and the clock is the cursor, so walking never moves the notes; notes are culled by the engine render window), `6 CLOSE` (`byte channel`; the player closed the panel), `7 AUDITION` (`byte channel`, `int index`; play this segment with audio **from the segment start** to one bar after the segment end, teleport the player to the **observation point** first, then return and re-open the panel; `index < 0` = play from the cursor without auto-stop). Channels: 0 SPEED, 1 X, 2 Y, 3 Z, 4 SCALE_X, 5 SCALE_Y, 6 SCALE_Z, 7 ROT_X, 8 ROT_Y, 9 ROT_Z. `easing` = `Easings` ordinal (0..33). A channel is a **partition** of `[0, chart end]`: contiguous segments, no gaps, no overlaps, every segment at least 0.25 beats long. **A jump is a value discontinuity on a shared boundary** (previous `endValue` != next `startValue`), so zero-length segments do not exist. `chart end` = max(beat derived from the chart length, last note beat + 8). Value limits (server-side): speed ±32 (negative = reverse flow, 0 = frozen), transform ±256, scale 0.01–16, rotation ±720. Rejected while playing. |

### Direction Server → Client

| Opcode | Name | Payload | Notes |
|---|---|---|---|
| 2 | `CHART_META` | `String songName`, `long lengthMs`, `long offsetMs`, `int bpmCount`, `{double beat, double bpm}` × bpmCount, `int subdivCount`, `{double startBeat, int noteValue}` × subdivCount | Live (M2). Sent on session open, on play start, after BPM edits, on active-track change and after subdivision edits. `subdivCount` describes the **active track's** subdivision grid (phase A, `4/noteValue` step) so the mod timeline HUD draws the same lines as the in-world ruler. |
| 3 | `TRANSPORT_PLAY` | `double fromMs`, `float speed` | Live. Only usable after `AUDIO_PUSH_ACK(ok=1)`; otherwise the mod replies `ERROR(105)`. `speed` is always `1.0` for now. |
| 4 | `TRANSPORT_PAUSE` | (none) | Live (M2). |
| 5 | `TRANSPORT_SEEK` | `double toMs` | Live (M2). |
| 6 | `TRANSPORT_STOP` | (none) | Live. |
| 7 | `SET_LOOP` | `double aMs`, `double bMs` | Live (M2). `bMs < 0` clears the loop. Both sides loop their own range; reconcile corrects drift. |
| 8 | `SET_SPEED` | `float speed` | Reserved (V1). Speed = resampling (pitch shifts); pitch-preserving speed is a later milestone. |
| 9 | `PING` | `long nonce` | Reserved (V1). |
| 101 | `HELLO_ACK` | `boolean ok`, `String serverVersion`, `String sessionId` | `ok=false` = protocol mismatch; the mod stops retrying. `sessionId` is always `""`. |
| 106 | `AUDIO_PUSH_START` | `String transferId`, `String songFolder`, `String fileName`, `long totalBytes`, `String sha256`, `int chunkSize`, `int totalChunks` | Sent automatically after a successful HELLO. `sha256` is lowercase hex. |
| 107 | `AUDIO_PUSH_CHUNK` | `String transferId`, `int index`, `int length`, `byte[length]` | `index` starts at 0. Server throttles on the main thread (default 16 chunks × 65536 B/tick ≈ 1 MiB/tick). |
| 108 | `AUDIO_PUSH_END` | `String transferId` | Client then verifies and responds `AUDIO_PUSH_ACK`. |
| 109 | `CHART_NOTES` | `int noteCount`, `{double beat, byte type}` × noteCount | Full snapshot for the mod timeline HUD, ascending by beat, `type` = `NoteType` ordinal (0=TAP, 1=LOOK, 2=HOLD, 3=DODGE), capped at 65536 markers. Sent on the mod-ready edge, on play start and after every note change. The mod replaces its cache or drops the whole frame when malformed. |
| 111 | `VIEW_STATE` | `int zoomIndex`, `double barBlocks`, `int levelCount`, `double cursorMs` | Live (v1.1). Sent on the mod-ready edge, on zoom change and periodically while editing. Feeds the timeline HUD window center (`cursorMs`) and the current world-grid zoom display. |
| 114 | `EDIT_STATE` | `boolean ok`, `String reason`, `byte type`, `double beat`, `double posX`, `double posY`, `double posZ`, `float scaleX`, `float scaleY`, `float scaleZ`, `float rotX`, `float rotY`, `float rotZ`, `int holdGroup`, `int holdGroupSize`, `int holdGroupIndex`, `byte holdBoundary`, `int holdGroupManual`, `double maxHalfWidth`, `double maxHalfHeight`, `double beatStep`, `boolean canUndo`, `boolean canRedo` | Live (v1.2). Selected-note snapshot + edit bounds + undo/redo availability. Sent on select, on every APPLY echo, after undo/redo, on active-track change, after delete and on session close/play start. `ok=false` = nothing selected, so the mod closes the panel. `holdGroup` is the resolved group id (`-1` = standalone), `holdGroupManual` the explicit override (`-1` = auto); `holdGroupSize/Index` describe the chain position (the panel shows 组号 + 头/身/尾 read-only). `beatStep` is the subdivision step at the selected beat; malformed frames are dropped. |
| 116 | `EVENT_STATE` | `boolean ok`, `String reason`, `int trackId`, `byte channel`, `byte preview`, `byte panel`, `int selectIndex`, `int count`, `{double startBeat, double endBeat, double startValue, double endValue, int easing}` × count | Live (v1.3). Whole event list of one channel of the active track, ascending by `startBeat`, plus the real-speed preview flag. `panel=1` is set by `EVENT_REQ LIST` (the mod opens the panel) and then kept in sync with the panel state, so later pushes neither re-open nor close it. `selectIndex` (0-based, `-1` = keep the current selection) tells the panel which segment to select — used by the in-world curve hover `✎ 编辑` button. `ok=false` (play start, session close) makes the mod close the panel; malformed frames are dropped. |
| 117 | `EVENT_HUD` | `int trackId`, `byte channel`, `byte preview`, `int count`, `{double startBeat, double endBeat, double startValue, double endValue, byte easing}` × count | Live (v1.3). Current-channel curve for the **mod timeline HUD** (same data as `EVENT_STATE`, no panel flag, `byte` easing). Sent on mod-ready, active-track change, active-channel change, every event mutation, undo/redo and session close — never per tick, so `VIEW_STATE` stays small. Malformed frames are dropped. |

### Chunking

Bukkit `Messenger` packets are ~32KB. Audio is pushed in fixed-size chunks advertised in `AUDIO_PUSH_START`:

- `chunkSize` defaults to 65536 B; `totalChunks = ceil(totalBytes / chunkSize)`.
- `AUDIO_PUSH_CHUNK` carries `index` + `length` + raw bytes. Both sides reject blobs over 1 MiB (`MAX_BLOB_BYTES`).
- The client writes chunks sequentially to `<gameDir>/rhythmc-audio/<songFolder>/<fileName>.part`.
- On `AUDIO_PUSH_END` the client checks total length + sha256, atomically moves `.part` to the final name, and sends `AUDIO_PUSH_ACK(ok=1)`. On failure it deletes `.part` and sends `ok=0` with a reason.
- No resume: a player who leaves mid-transfer restarts from chunk 0 on rejoin.

### Payload Encoding

All payloads use plugin-channel raw bytes. The frame is a big-endian `int` opcode followed by a `String sessionId` and the payload. Strings are encoded as `int byteLength` followed by UTF-8 bytes. Chunk bytes are encoded as `int byteLength` followed by raw bytes. All numbers are fixed-width big-endian. Booleans on the wire are a single byte (`0`/`1`). This deliberately avoids Java `writeUTF` and Minecraft `writeString`/varint string encoding drift. Unknown opcodes are ignored by both sides; malformed frames are dropped with a warning. `sessionId` is always the empty string and neither side validates or filters on it.

### Trust Model

- The sender is a Bukkit `Player` currently online on the server. Identity = that online player. No token, no session binding.
- The server pushes audio only to players whose `HELLO_ACK.ok=true`.
- Audio source is a server-side Dummy file resolved from plugin config (not user upload); the ChartMaker upload trust model is unchanged.
- No client input can cause the server to read arbitrary files: `songFolder`/`fileName` are validated against path traversal (`..`, `/`, `\`, `:` rejected).

### Lifecycle (audio push + transport)

1. Client joins the server and sends `HELLO` 1s after JOIN.
2. Plugin validates the protocol version and responds `HELLO_ACK`; on mismatch `ok=false` and the mod stops retrying.
3. On `ok=true` the plugin automatically pushes the Dummy song: `AUDIO_PUSH_START` → `AUDIO_PUSH_CHUNK`* → `AUDIO_PUSH_END`.
4. The mod writes chunks sequentially to `<gameDir>/rhythmc-audio/<songFolder>/<fileName>.part`.
5. After `AUDIO_PUSH_END` the mod verifies total length + sha256, atomically moves the file, and sends `AUDIO_PUSH_ACK(ok=1)`; failures delete `.part` and send `ok=0` + reason.
6. `/charter play [<from>]` requires `HELLO_ACK.ok=true` and `AUDIO_PUSH_ACK(ok=1)`; it starts an in-memory editor session playback. `TRANSPORT_PLAY` payload is `double fromMs` + `float speed` (always `1.0`).
7. Transport is plugin-clock-authoritative: plugin sends 3/4/5/6/7; the mod engine applies them and reports `STATE(103)` every 5 ticks and immediately after each op.
8. Reconcile: while playing, the plugin evaluates every `transport.reconcile-interval-ticks` (default 10): `|plugin ms − STATE.positionMs| > transport.sync-threshold-ms` (default 60) → `TRANSPORT_SEEK`; `STATE.playing=false` while the plugin plays → `TRANSPORT_PLAY` again. The reconciler stays silent for 3 periods after play/seek.
9. A-B loop: the plugin wraps its own clock at B and sends `TRANSPORT_SEEK(A)` on wrap; the mod engine also loops locally. Reconcile corrects drift.
10. Player quit cancels any incomplete transfer and tears down the session; rejoin restarts from 0 (no resume).
11. If no ACK arrives within 30s after `AUDIO_PUSH_END`, the server marks the push failed and notifies via `ERROR(105)`.
12. Timeline HUD data: `CHART_META(2)`, `CHART_NOTES(109)` and `VIEW_STATE(111)` are sent when the mod becomes ready and on play start; `CHART_NOTES` also after every note change, `CHART_META` after BPM edits, `VIEW_STATE` on zoom change and periodically while editing. All are full snapshots; the mod never requests them.
13. Note editing: the mod sends `EDIT_REQ(0)` on middle-click; the plugin picks the note under the crosshair (current track only), stores the selection and answers `EDIT_STATE(ok=1)`, which opens the mod property panel (draggable, position persisted in the mod config dir). Panel changes go back as `EDIT_REQ(4 APPLY)`; the plugin validates/clamps, mutates the model, updates the affected track runtime **in place** (no display re-creation, so no flicker) and echoes `EDIT_STATE`. `EDIT_REQ(4 APPLY)` while nothing is selected just answers `ok=false`. HOLD conversion is refused unless the note already sits at `Y=-1`; HOLD chains are auto-grouped from consecutive HOLD notes (same lane, next subdivision point), can be cut with the `holdBoundary` head/tail flags, and can be overridden with an explicit `holdGroupManual` id (merges non-adjacent / cross-lane notes; auto groups then extend the manual group and skip its id). Every mutation (placement, chain placement, deletion, type/transform edits, clone, BPM, subdivisions, track CRUD) pushes a deep-copied chart snapshot onto a 64-deep undo stack; `Ctrl+Z` / `Ctrl+Y` / `Ctrl+Shift+Z` (throttled by the mod) and `/charter undo|redo` drive it. Starting playback deselects and closes the panel; edits are rejected while playing.
14. Track events: the mod opens the event panel with `EVENT_REQ(0 LIST, channel)` (the `G` keybind or `/charter events`) and receives `EVENT_STATE(panel=1)`; every edit goes back as `EVENT_REQ(1..4)` and is answered with a fresh `EVENT_STATE` (full list, `panel` unchanged). Closing the panel sends `EVENT_REQ(6 CLOSE)`; starting playback or closing the session sends `ok=false`. Edits are structural: `SPLIT` cuts one segment into two (exactly +1 segment, curve unchanged), `REMOVE` merges a segment into a neighbour (-1 segment, refused for the last one - the panel then offers "reset channel" = `CLEAR`), `UPDATE` moves a shared boundary and/or changes the boundary values (`valueMode` decides whether the next segment's start value follows). Edit-mode notes are rendered from a projection that shares no event data with the model (see the preview paragraph), so an edit never resizes or moves the ordinary note displays. `EVENT_REQ(5 PREVIEW)` switches the isolated channel preview. The normal edit view is an **absolute projection**: it never applies any event channel, so notes are welded to the ruler grid (distance = beat x grid scale) and neither walking nor editing an event moves them. The preview layer is a separate runtime instance that applies only the whole current channel (the other nine channels stay neutral), is anchored at the observation point and uses the cursor as its clock (so walking never moves the notes either); notes are culled by the engine render window; it is rebuilt when the channel/track/zoom changes and destroyed when the preview is switched off, playback starts or the session closes. **Observation point** (server-side rendering anchor, no protocol impact): the paste centre of the per-track arena structure (`origin + editor.arena.offset-*`, one arena per track); playback/audition/isolated preview teleport the player there and the **judgement plane** sits `editor.judge-plane-offset` (default 3.0) blocks in front of it; the edit view keeps its own judgement plane exactly on the cursor's ruler grid line. The edit-mode flow velocity is `playerSpeed x SpeedEvent` where `playerSpeed` is the editor grid scale, which is why 1.0x stays on the ruler grid and negative speed flows backwards. `EVENT_REQ(7 AUDITION)` plays one segment with audio from the segment start to one bar after its end (player teleported to the observation point), stops automatically, returns the player and re-opens the panel; the panel also offers a `只预览当前通道` toggle (below the play buttons) that maps to `EVENT_REQ(5 PREVIEW)`; the panel has no play-from-cursor button any more (full-effect listening vs isolated single-channel preview are deliberately different things). The same channel's curve is mirrored to the mod timeline HUD via `EVENT_HUD(117)` (pushed on mod-ready, track/channel change, every mutation and undo/redo, never per tick); the HUD draws a vertical line where a boundary is discontinuous. Event edits are undoable (`/charter events …` and the panel share the same history; segment numbers are 1-based in chat, 0-based on the wire).

### Config Keys

- Plugin `plugins/RhythMC-Charter-V2/config.yml`: `audio.chunk-size` (default 65536), `audio.chunks-per-tick` (default 16), `audio.dummy.song-folder` (default `dummy_song`), `audio.dummy.file` (default `audio/dummy_song/<file>.flac`, relative to the plugin data folder).
- Plugin transport/reconcile: `transport.sync-threshold-ms` (default 60), `transport.reconcile-interval-ticks` (default 10).
- Plugin editor (local state, no peer dependency): `editor.default-speed`, `editor.default-length-ms`, `editor.render-distance`, `editor.grid.*` (incl. `bar-blocks` default level and `zoom-levels`), `editor.corridor.*`, `editor.ruler.*`, `editor.placement.*`, `editor.follow.*`, `editor.events.*` (curve sampling / scale mode; segments no longer have a default length), `editor.arena.*` (`schematic`, `offset-x`, `offset-y`, `offset-z`; the per-track arena paste point, whose block centre is the observation point) and `editor.judge-plane-offset` (default 3.0; the judgement plane sits that many blocks in front of the observation point).
- Mod storage: `<gameDir>/rhythmc-audio/<songFolder>/`. FLAC playback uses low-level `org.jflac.FLACDecoder` (`decodeFrames()`), plus the existing vorbis/mp3 SPI fallback chain.

## Chart File Formats (local, no protocol impact)

Chart files never travel over `rhythmc:charter_audio`; they are read and written on the
server side only, so they are not a cross-repo wire contract. They are documented here
because the compiled output must be byte-compatible with what RhythMC-Reborn /
RhythMC-Preview deserialize.

- **RMCD v1** — `RhythMC Editor Chart Data`, the editor's authoritative persistence format:
  a single UTF-8 JSON file (`*.rmcd`) that losslessly stores META, every track and note, and
  the editor-only state RMCC cannot express (track names, stable note ids, subdivision
  segments, cursor). Musical values (beats, BPM, event values) are exact reduced fractions
  (`{"num":n,"den":d}`); geometry (position/scale/rotation) stays decimal; `offsetMs` /
  `lengthMs` stay plain integer milliseconds. Full specification:
  `.agent/RMCD-FORMAT.md` (single source of truth).
- **RMCC** — the runtime chart format consumed by RhythMC-Reborn / RhythMC-Preview
  (`world.rmcc`, `nether.rmcc`, `end.rmcc`, `void.rmcc` + `manifest.yml`). Owned by those
  repos; this plugin only ever writes it, per the mapping in `.agent/RMCD-FORMAT.md` §8.
  The RMCD → RMCC compiler is specified there but **not implemented yet**; no `/charter`
  save/open/compile command exists at the time of writing.

## Out of Scope

There is no HTTP, WebSocket, DB, auth/session, resource-pack, or frontend DTO contract in this repo. Do not reintroduce any of these. `rhythmc:chart_preview` belongs to RhythMCChartMaker + RhythMC-Preview and must not be modified from here. Not in v1: `SET_SPEED`/pitch-preserving speed, in-world event handle dragging (B3), format painter / editor hotbar tools, and judging/gameplay scoring. Chart persistence beyond the documented RMCD format (implementation is a later milestone) is also out of scope for now — the note/event property editing changes in-memory state only.
