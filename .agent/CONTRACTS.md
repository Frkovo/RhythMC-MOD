# CONTRACTS.md — RhythMC-MOD

跨仓通道契约登记。本文档与 `E:/Dev/RhythMC-Charter-V2/.agent/CONTRACTS.md` 保持同步（同一契约，两端各存一份）。

## rhythmc:charter_audio（本 mod 实现 · 客户端端）

- **所有权**：RhythMC-Charter-V2（服务端端）+ RhythMC-MOD（客户端端）。2026-09-08 用户确认以独立新通道落地 MVP，不复用/不重叠 `rhythmc:chart_preview`（其所有权在 RhythMCChartMaker + RhythMC-Preview）。
- **用途（单一）**：制谱会话内的音频传输控制（播放/暂停/seek/循环/变速）+ 音频本地匹配。
- **信任模型**：发送者 = 在线玩家连接；指令仅对已完成 HELLO_ACK 且 sessionId 一致的会话生效。

### 帧

`[int opcode][string sessionId][payload]`；字符串 = `int byteLength + UTF-8`；数值大端固定宽度；禁用 `writeUTF`/MC varint。Fabric 侧以 `CustomPayload` + `PacketCodec.of` 原样包装字节（ChartMaker 同款范式）。

### opcode

| 方向 | opcode | 名称 | 载荷 |
|------|--------|------|------|
| C→S | 1 | HELLO | `int protocol, string modVersion, int capabilities` |
| S→C | 2 | CHART_META | `string songFolder, string songName, string audioHint` |
| S→C | 3 | TRANSPORT_PLAY | `double fromMs, float speed` |
| S→C | 4 | TRANSPORT_PAUSE | — |
| S→C | 5 | TRANSPORT_SEEK | `double toMs` |
| S→C | 6 | TRANSPORT_STOP | — |
| S→C | 7 | SET_LOOP | `double aMs, double bMs`（bMs<0 = 清除） |
| S→C | 8 | SET_SPEED | `float speed` |
| S→C | 9 | PING | `long clientNonce` |
| C→S | 10 | TRANSPORT_REQ | `byte action, int bars`（v1.1：mod 快捷键回传；action 0=play/pause 1=stop 2=seek±bars 3=loopA 4=loopB 5=clear） |
| S→C | 101 | HELLO_ACK | `byte ok, string serverVersion, string sessionId` |
| C→S | 102 | CHART_STATUS | `byte ok, string reason, string audioSha1, long lengthMs` |
| C→S | 103 | STATE | `byte playing, double positionMs, float speed` |
| C→S | 104 | PONG | `long clientNonce` |
| 双向 | 105 | ERROR | `int code, string message` |

### 语义

- `protocol`（当前 = 1）不匹配 → HELLO_ACK.ok=0，插件拒绝创建会话。
- HELLO_ACK.ok=1 后：双方每帧附 `sessionId`，不匹配帧直接丢弃。
- STATE 每 10 tick 上报（与服务端对账周期一致，插件偏差 > 60ms 重 seek）。
- capabilities 位：bit0 seek / bit1 loop / bit2 speed(重采样变调) / bit3+ 预留。
- 音频匹配：`.minecraft/rhythmc-audio/<song_folder>/`，audioHint → audio/song/music/bgm/track/preview.* → 目录首个音频；CHART_STATUS.ok=1 时带 SHA-1 与时长。匹配失败 = 静音审计态（插件端 HUD 告警）。
- mod 快捷键（播放/暂停、±1 小节、A/B 设点）= C→S TRANSPORT_REQ(10)，动作全部由插件 Transport 执行——**mod 本地按键直控音频被禁止**（避免双时钟源分叉，§11.5）。
- 客户端按键分类 `rhythmc-mod:charter`（KeyBinding.Category.create），默认键位 K/J/L/O/P。

### 变更流程

改 opcode/载荷/语义 = 契约变更：同步修改两仓实现 + 两仓本文档 + Charter-V2 设计稿 §11/附录 B，同一任务内完成，重大变更需用户确认。
