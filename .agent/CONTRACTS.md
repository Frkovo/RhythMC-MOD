# Contract Map

Updated: 2026-09-13 (note editing: EDIT_REQ/EDIT_STATE; RMCD format; CHART_NOTES; VIEW_ZOOM/VIEW_STATE/VIEW_SEEK)

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
| 114 | `EDIT_STATE` | `boolean ok`, `String reason`, `byte type`, `double beat`, `double posX`, `double posY`, `double posZ`, `float scaleX`, `float scaleY`, `float scaleZ`, `float rotX`, `float rotY`, `float rotZ`, `int holdGroup`, `int holdGroupSize`, `int holdGroupIndex`, `byte holdBoundary`, `int holdGroupManual`, `double maxHalfWidth`, `double maxHalfHeight`, `double dodgeScale`, `double beatStep`, `boolean canUndo`, `boolean canRedo` | Live (v1.2). Selected-note snapshot + edit bounds + undo/redo availability. Sent on select, on every APPLY echo, after undo/redo, on active-track change, after delete and on session close/play start. `ok=false` = nothing selected, so the mod closes the panel. `holdGroup` is the resolved group id (`-1` = standalone), `holdGroupManual` the explicit override (`-1` = auto); `holdGroupSize/Index` describe the chain position (the panel shows 组号 + 头/身/尾 read-only). `beatStep` is the subdivision step at the selected beat; malformed frames are dropped. |

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

### Config Keys

- Plugin `plugins/RhythMC-Charter-V2/config.yml`: `audio.chunk-size` (default 65536), `audio.chunks-per-tick` (default 16), `audio.dummy.song-folder` (default `dummy_song`), `audio.dummy.file` (default `audio/dummy_song/<file>.flac`, relative to the plugin data folder).
- Plugin transport/reconcile: `transport.sync-threshold-ms` (default 60), `transport.reconcile-interval-ticks` (default 10).
- Plugin editor (local state, no peer dependency): `editor.default-speed`, `editor.default-length-ms`, `editor.render-distance`, `editor.grid.*` (incl. `bar-blocks` default level and `zoom-levels`), `editor.corridor.*`, `editor.ruler.*`, `editor.placement.*`, `editor.follow.*`.
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

There is no HTTP, WebSocket, DB, auth/session, resource-pack, or frontend DTO contract in this repo. Do not reintroduce any of these. `rhythmc:chart_preview` belongs to RhythMCChartMaker + RhythMC-Preview and must not be modified from here. Not in v1: `SET_SPEED`/pitch-preserving speed, track event editing (transform/scale/rotation/speed events), format painter / editor hotbar tools, and judging/gameplay scoring. Chart persistence beyond the documented RMCD format (implementation is a later milestone) is also out of scope for now — the note property panel edits in-memory state only.
