# P4 有界协议载荷与私信队列

## 载荷

纯 Java 设计模块中的 `ProtocolPayloadCodec` 接受已校验 Protocol 和字段值。它拒绝未知字段、缺少必填字段、类型不符的数值、越界位置、不合法资源 ID，以及超过协议自身 `maxPayloadBytes` 的 UTF-8 载荷。字段按名称排序后编码为 `长度:名称=长度:值` 片段，避免分隔符歧义。协议字段总数、名称、类型和最大载荷由现有设计模型约束。

## 投递

模拟模块的 `ProtocolMailboxHub` 只在创建线程上运行。双方必须是登记参与者，且双方都持有指定协议许可。每步最多 64 次发送，每个收件箱最多 128 条，队列总量最多 4096 条，最多 256 名参与者。每条消息最多 4096 字节，TTL 为 1..72000 个逻辑步。每个逻辑步发送序号从零开始并按发送者独立递增。收件箱按发送者 AgentId、发送逻辑步和发送序号投递。推进逻辑步时会回收过期消息，并返回按收件人和发送者排序的过期结果。

`ProtocolMailboxCheckpoint` 保存逻辑 tick、参与者、双方协议许可、待投递消息和当前逻辑步的发送序号。`ProtocolMailboxCheckpointCodec` 使用版本化、大端二进制编码，最多 20 MiB；解码检查容量、许可、唯一序号、消息寿命与尾随数据。恢复后的队列在相同输入下继续产生相同投递与过期结果。自动化测试覆盖编码规范性、损坏输入拒绝和恢复等价。

Paper 玩家可通过 `/genesis protocol` 解锁并装备公开入门芯片。同服双方必须在线、具有 `stargenesis.protocol` 权限并装备 `genesis:protocol`，发送方使用 `/genesis protocol send <player> <sequence>` 发送符合 starter Protocol 的整数载荷。队列仅在全局调度器上访问，每秒推进一个逻辑步，玩家 PDC 只在各自实体调度器上读写。`/genesis protocol inbox` 打开收件箱，点击单条消息删除；也可用 `/genesis protocol inbox clear` 清空收件箱。

待投递队列是服务端内存态。消息写入收件人 PDC 后才从队列确认移除。PDC Inbox checkpoint 最多 128 条、最多 600,000 字节，绑定收件人 UUID；每条同服消息带服务运行标识，防止服务重启后 tick/sequence 从零开始造成收件箱去重冲突。checkpoint v2 增加最多 1,700 条跨服回执，并兼容读取 v1。跨服发件箱保存签名数据包并在收到目标回执后删除；目标服务器先验证 peer 专属 HMAC、目标 ID、期限、协议字段、收件权限和已装备芯片，再把收件消息与去重回执同写到玩家 PDC。发件箱每 5 秒重试，目标的 PDC 回执抑制确认前重复转发。Minecraft 玩家数据最终落盘仍遵循 Paper/Folia 保存周期，跨服消息与玩家 PDC 的崩溃耐久性尚未实服验收。

源端发件箱将完整数据包写入同目录临时文件并调用 `FileChannel.force(true)`，再以 `ATOMIC_MOVE` 提交。文件系统不支持原子移动时写入失败，不退回普通移动；同一 message ID 与相同数据可重试，不同数据会被拒绝。这只保证本地发件箱文件提交路径，尚未替代 Paper/Folia 玩家 PDC 的保存周期或代理端异常断电验收。

## 跨服务器代理

跨服私信使用 BungeeCord 兼容代理的玩家插件消息转发。每个 Paper/Folia 后端配置唯一 `protocol-relay.server-id`，并在 `protocol-relay.peers` 中为每个对端配置 Base64 密钥。两端对同一 peer 使用相同的随机密钥，不同 peer 使用不同密钥。密钥至少 32 个随机字节。PowerShell 可生成一个密钥：`[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`。密钥只应通过受控渠道发送给该 peer 的服务器管理员。

后端 A 配置 B，后端 B 配置 A。`/genesis protocol relay <server-id> <player-uuid> <sequence>` 会把消息写入本地持久发件箱。目标 ID 必须和代理配置的服务器名一致。目标玩家需在线、具有协议权限并装备同一入门芯片。代理需允许 BungeeCord 插件消息转发。Velocity 部署需启用 BungeeCord 插件消息兼容选项。在线玩家承载代理消息，因此源服务器至少需要一名在线玩家才能转发数据包或回执。P6 的 `/genesis factory remote` 复用同一 peer ID 和 HMAC 配置，但使用独立请求类型、发件箱及服务器端请求回执，不要求协议芯片，并须显式配置 `factory-relay.enabled: true`。

```yaml
protocol-relay:
  enabled: true
  server-id: hub_a
  peers:
    hub_b: "仅供 hub_a 与 hub_b 使用的随机 Base64 密钥"
```

目标后端配置指向源后端的相同密钥。包使用 HMAC-SHA256，最大生命周期 120 秒，创建时间允许最多领先目标时钟 30 秒，最大编码大小 8 KiB。已创建数据包可以在其到期前持续重试，创建超过 30 秒仍有效。代理收到 `Forward` 和目标服务器名后，将 `sg:relay`、载荷长度和签名包转交目标后端的 BungeeCord 通道。

2026-11-24 Paper 1.21.11-132 与 Folia 1.21.11-14 各用两个真实后端进程、Velocity 3.5.1-615 和离线 Mineflayer 客户端通过 12 项检查，覆盖 UUID 转发、芯片装备、消息投递、认证回执、离线 40 秒后重新连接、发件箱删除，以及清空收件箱后重放同一签名包的去重。证据为 `evidence/p4/paper-retry-window-02-20261124.json` 和 `evidence/p4/folia-retry-window-01-20261124.json`。双端掉电恢复和真人多玩家体验仍待验收。

`scripts/protocol_proxy_smoke.mjs` 支持 `--kind paper|folia`。`--backend-jar` 可指定已启动解包后的服务端 JAR，配合 `--runtime-directory` 直接从共享 classpath 读取运行库，世界、配置、插件数据及日志写入测试目录。此模式预留 256 MiB 空间。`--detach` 返回独立 worker PID，最终 JSON 含构建哈希、检查结果和三台进程退出码。

模拟原语不触碰真实库存、货币、网络或文件。Paper 适配层只使用 Bukkit/Paper 玩家 API 和 PDC。

自动化合同测试覆盖有类型载荷验证、双方许可、越界拒绝、每步/每信箱/总量预算、逻辑步过期、确定性收件顺序及 owner-thread 限制。
