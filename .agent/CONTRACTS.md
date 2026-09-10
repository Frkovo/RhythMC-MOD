# Contract Map

Updated: 2026-09-10

## Contract Change Definition

A task crosses a contract boundary when it changes any of these:

- `rhythmc:charter_audio` plugin channel opcode set, direction, payload schema, chunking protocol, lifecycle, or trust model.
- Transport semantics (play/pause/seek/stop/loop/speed clocking) shared between the Paper plugin and the Fabric client mod.
- Audio push semantics (chunking, hashing, ACK, client storage layout).
- Plugin config keys or mod config keys that both sides depend on.

Any opcode/payload change must be made in both repos plus `docs/charter-design.md` in the same task (design doc §2.3, §11.6).

## The Only Contract: `rhythmc:charter_audio`

A bidirectional Bukkit plugin channel between the RhythMC-MOD client mod and this Paper plugin (RhythMC-Charter-V2). Opcodes and payload format must match `CharterAudioChannel.java` (mod) and `CharterAudioBridge.java` (plugin) on both sides.

Protocol version: `PROTOCOL_VERSION = 1` (fresh baseline; M0).

### Direction Client → Server

| Opcode | Name | Payload | Notes |
|---|---|---|---|
| 1 | `HELLO` | `int protocolVersion`, `String modVersion`, `int capabilities` | Sent 1s after JOIN. Retried every 100 ticks until handshake. `caps` bits: 1=SEEK, 2=LOOP, 4=SPEED. |
| 10 | `TRANSPORT_REQ` | `byte action`, `int bars` | Reserved (V1). |
| 11 | `AUDIO_PUSH_ACK` | `String transferId`, `byte ok`, `String reason` | Audio transfer verification result. `ok=0` carries a human-readable reason. |
| 102 | `CHART_STATUS` | `boolean ok`, `String reason`, `String sha1`, `long lengthMs` | Reserved (V1). |
| 103 | `STATE` | `boolean playing`, `double positionMs`, `float speed` | Reserved (V1). |
| 104 | `PONG` | `long nonce` | Reserved (V1); answers S→C `PING`. |
| 105 | `ERROR` | `String message` | Human-readable. Plugin logs it and forwards to the sender's chat. |

### Direction Server → Client

| Opcode | Name | Payload | Notes |
|---|---|---|---|
| 2 | `CHART_META` | (TBD) | Reserved (V1). |
| 3 | `TRANSPORT_PLAY` | `double fromMs`, `float speed` | M0: only usable after `AUDIO_PUSH_ACK(ok=1)`. If not downloaded, the mod replies `ERROR(105)`. |
| 4 | `TRANSPORT_PAUSE` | (none) | Reserved (V1). |
| 5 | `TRANSPORT_SEEK` | `double toMs` | Reserved (V1). |
| 6 | `TRANSPORT_STOP` | (none) | M0 supported. |
| 7 | `SET_LOOP` | `double aMs`, `double bMs` | Reserved (V1). |
| 8 | `SET_SPEED` | `float speed` | Reserved (V1). |
| 9 | `PING` | `long nonce` | Reserved (V1). |
| 101 | `HELLO_ACK` | `boolean ok`, `String serverVersion`, `String sessionId` | `ok=false` = protocol mismatch; the mod stops retrying. M0: `sessionId` is always `""`. |
| 106 | `AUDIO_PUSH_START` | `String transferId`, `String songFolder`, `String fileName`, `long totalBytes`, `String sha256`, `int chunkSize`, `int totalChunks` | Sent automatically after a successful HELLO. `sha256` is lowercase hex. |
| 107 | `AUDIO_PUSH_CHUNK` | `String transferId`, `int index`, `int length`, `byte[length]` | `index` starts at 0. Server throttles on the main thread (default 16 chunks × 65536 B/tick ≈ 1 MiB/tick). |
| 108 | `AUDIO_PUSH_END` | `String transferId` | Client then verifies and responds `AUDIO_PUSH_ACK`. |

### Chunking

Bukkit `Messenger` packets are ~32KB. Audio is pushed in fixed-size chunks advertised in `AUDIO_PUSH_START`:

- `chunkSize` defaults to 65536 B; `totalChunks = ceil(totalBytes / chunkSize)`.
- `AUDIO_PUSH_CHUNK` carries `index` + `length` + raw bytes. Both sides reject blobs over 1 MiB (`MAX_BLOB_BYTES`).
- The client writes chunks sequentially to `<gameDir>/rhythmc-audio/<songFolder>/<fileName>.part`.
- On `AUDIO_PUSH_END` the client checks total length + sha256, atomically moves `.part` to the final name, and sends `AUDIO_PUSH_ACK(ok=1)`. On failure it deletes `.part` and sends `ok=0` with a reason.
- M0 has no resume: a player who leaves mid-transfer restarts from chunk 0 on rejoin.

### Payload Encoding

All payloads use plugin-channel raw bytes. The frame is a big-endian `int` opcode followed by a `String sessionId` and the payload. Strings are encoded as `int byteLength` followed by UTF-8 bytes. Chunk bytes are encoded as `int byteLength` followed by raw bytes. All numbers are fixed-width big-endian. This deliberately avoids Java `writeUTF` and Minecraft `writeString`/varint string encoding drift. Unknown opcodes are ignored by both sides; malformed frames are dropped with a warning. M0 convention: `sessionId` is always the empty string and neither side validates or filters on it.

### Trust Model

- The sender is a Bukkit `Player` currently online on the server. Identity = that online player. No token, no session binding.
- The server pushes audio only to players whose `HELLO_ACK.ok=true`.
- M0 audio source is a server-side Dummy file resolved from plugin config (not user upload); the ChartMaker upload trust model is unchanged.
- No client input can cause the server to read arbitrary files: `songFolder`/`fileName` are validated against path traversal (`..`, `/`, `\`, `:` rejected).

### Lifecycle (M0)

1. Client joins the server and sends `HELLO` 1s after JOIN.
2. Plugin validates the protocol version and responds `HELLO_ACK`; on mismatch `ok=false` and the mod stops retrying.
3. On `ok=true` the plugin automatically pushes the Dummy song: `AUDIO_PUSH_START` → `AUDIO_PUSH_CHUNK`* → `AUDIO_PUSH_END`.
4. The mod writes chunks sequentially to `<gameDir>/rhythmc-audio/<songFolder>/<fileName>.part`.
5. After `AUDIO_PUSH_END` the mod verifies total length + sha256, atomically moves the file, and sends `AUDIO_PUSH_ACK(ok=1)`; failures delete `.part` and send `ok=0` + reason.
6. `/charter play [<xx>s]` and `/charter stop` are rejected until the sender's `AUDIO_PUSH_ACK(ok=1)` arrives (message: audio not downloaded yet). `TRANSPORT_PLAY` payload is `double fromMs` + `float speed` (M0 always `1.0`).
7. Player quit cancels any incomplete transfer; rejoin restarts from 0 (no resume in M0).
8. If no ACK arrives within 30s after `AUDIO_PUSH_END`, the server marks the push failed and notifies via `ERROR(105)`.

### Config Keys

- Plugin `plugins/RhythMC-Charter-V2/config.yml`: `audio.chunk-size` (default 65536), `audio.chunks-per-tick` (default 16), `audio.dummy.song-folder` (default `dummy_song`), `audio.dummy.file` (default `audio/dummy_song/<file>.flac`, relative to the plugin data folder).
- Mod storage: `<gameDir>/rhythmc-audio/<songFolder>/`. FLAC playback uses `org.jflac:jflac-codec:1.5.2` (`FlacAudioFileReader` / `FlacFormatConversionProvider`), plus the existing vorbis/mp3 SPI fallback chain.

## Out of Scope

There is no HTTP, WebSocket, DB, auth/session, resource-pack, or frontend DTO contract in this repo. Do not reintroduce any of these. `rhythmc:chart_preview` belongs to RhythMCChartMaker + RhythMC-Preview and must not be modified from here. M0 does not include beat/bar transport semantics, playback sync reconciliation, loop/speed/seek, or audio resume.
