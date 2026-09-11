# AGENTS.md

RhythMC-MOD is the Fabric client mod (Minecraft 1.21.11, yarn mappings, Java 21) for the
RhythMC charter editor. It is the client half of the `rhythmc:charter_audio` plugin channel,
talking to the Paper plugin **RhythMC-Charter-V2** (parent repository).

## Read First

1. `AGENTS.md` (this file)
2. `.agent/CONTRACTS.md` — authoritative `rhythmc:charter_audio` contract
3. `../docs/charter-design.md` — full design document (Chinese, in the parent repo)
4. `../src/main/resources/config.yml` — server-side audio push settings (parent repo)

## Repository Map

| Area | Path | Responsibility |
|---|---|---|
| This repo | `E:/Dev/RhythMC-Charter-V2/RhythMC-MOD` | Fabric client mod: download receiver, HUD progress, audio decode/playback |
| Server plugin | `E:/Dev/RhythMC-Charter-V2` | Paper plugin: handshake, audio push service, `/charter` commands |
| Sibling repos | `E:/Dev/RhythMCChartMaker`, `E:/Dev/RhythMC-Preview` | Own the separate `rhythmc:chart_preview` channel (chart + audio upload) |
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
4. `../docs/charter-design.md` when behavior/design changes.

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
| C→S | 103 | `STATE` | `byte playing, double positionMs, float speed`（每 5 tick + 状态变更即发，对账数据源） |
| C→S | 105 | `ERROR` | `String message` |
| S→C | 2 | `CHART_META` | `String songName, long lengthMs, long offsetMs, int bpmCount, {double beat,double bpm}[]` |
| S→C | 3 | `TRANSPORT_PLAY` | `double fromMs, float speed` |
| S→C | 4 | `TRANSPORT_PAUSE` | — |
| S→C | 5 | `TRANSPORT_SEEK` | `double toMs` |
| S→C | 6 | `TRANSPORT_STOP` | — |
| S→C | 7 | `SET_LOOP` | `double aMs, double bMs`（bMs<0 清除） |
| S→C | 101 | `HELLO_ACK` | `boolean ok, String serverVersion, String sessionId` |
| S→C | 106 | `AUDIO_PUSH_START` | `String transferId, String songFolder, String fileName, long totalBytes, String sha256, int chunkSize, int totalChunks` |
| S→C | 107 | `AUDIO_PUSH_CHUNK` | `String transferId, int index, int length, byte[length]` |
| S→C | 108 | `AUDIO_PUSH_END` | `String transferId` |

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
| `src/client/java/.../client/input/CharterKeybinds.java` | Transport keybinds → `TRANSPORT_REQ(10)` |
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
