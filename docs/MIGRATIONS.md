# 版本与迁移

## P0 基线

工程版本 `4.0.0-SNAPSHOT`；公共设计 schemaVersion=1；配置 config-version=1；模拟接口 `genesis-sim/1`。这是研发构建，不是 P0–P11 正式发布完成声明。

持久设计格式、配置版本、引擎版本和插件发布版本分别升级。加载未知设计或配置版本时明确拒绝，不猜测兼容性。

## 设计 v0 → v1

`DesignCodec.migrateV0(String)` 验证旧文档哈希，显式把 `metadata.revision` 改为 `metadata.version`、schemaVersion 改为1，重新计算内容哈希，并返回前后哈希、迁移后的 JSON 和不可变对象。普通 `load` 不执行迁移。

v0 是本项目带测试的遗留契约样例，不声称曾在生产服发布。迁移前保留原文与哈希，不覆盖唯一副本；若依赖仍固定旧哈希，必须显式更新依赖并重新验证。Schema/示例/拒绝用例在 stargenesis-design 模块内。

## 配置与账本

P0 不提供在线 reload 命令。调整 config.yml 后通过受控重启应用；越界预算、未知 config-version 或启用真实结算会拒绝插件启动。先备份插件数据目录，再升级。

P1 实验室的磁盘快照与事件回放使用有界 JSON 文件，启动时校验格式和预算后再恢复。P2 个人蓝图继续使用 schemaVersion=1，通过 DesignCodec 校验 SHA-256；每位作者最多64份，文件名由蓝图 ID 哈希生成。只在显式 save 时持久化工作区；保存会验证打开时的基准哈希，检测到并发变更时拒绝覆盖。共享编辑仍写回作者目录，授权以当前持久文档为准；旧 schema-v1 私有文档不需要迁移。MemoryEventOutbox 仍不持久化，P9 已有持久 saga，但没有真实经济/物品 adapter，不能宣称重启后存在真实资产恢复。

P3 typed IR 使用独立 `TYPED_PROGRAM` schemaVersion=1，不改变原 schema-v1 Program 文档；旧 Program 只通过显式 `ProgramCompiler` 编译。typed-program 文档使用规范 JSON SHA-256，未知 schema、原语、字段或类型拒绝加载。Paper 存储按作者 UUID 隔离，每玩家64份，显式保存并检查打开时的基准哈希。当前尚无 schema v0 或兼容性迁移。

P4 radio wire message 引用已封存 Protocol ID 与 SHA-256，不引入 wire message 格式变化。PDC chip 记录 protocol ID/hash，不构成数字签名或身份授权。Paper 将 mailbox、发送/确认事件、sequence 去重、ContractRuntime 状态以及当前 Protocol/Contract ID 与 hash 写入 `radio-state.json` schemaVersion=1，文件上限16 MiB并通过原子替换更新。入队、确认、成功处理的 Contract 消息和关服时立即保存，逻辑 tick 每20步检查点保存。未知 schema、pinned hash 变化、tick 不一致或超预算时拒绝恢复，不自动丢弃或迁移数据。在线端点不落盘，重启后玩家须重新 join。当前固定 service Contract 定义变化需要后续显式迁移，不能直接替换后复用旧快照。

P5 ecology replay 使用 schemaVersion=1 和固定 `genesis-ecology/1` 引擎版本，包含玩家所有权、world ID、seed、配置、初态哈希、每步输入、状态哈希和事件哈希。Paper 按 UUID 存储，文件上限4 MiB并原子替换。读取时拒绝未知字段、所有权变化、超预算文件、未知引擎和任何重演哈希差异。变更生态算法需提升引擎版本，不能静默重算旧 replay。

P5 可选时长将已存在的 config.maxTicks 允许范围扩到1–1536，默认500与默认50,000次账本证据保持不变。新长局按 `max(50,000, ticks * 32 * 2)` 预留证据，最大98,304，不改变账本100,000、个体32、代数8和默认出生512的硬预算。格式与每步算法均未改变，因此仍为 schemaVersion=1 / `genesis-ecology/1`；旧文件按原配置原哈希恢复，不升级时长。固定样本 `stargenesis-paper/src/test/resources/ecology-v1-default.json` 从 `9be9cb5` 的构建产物导出并校验原初态/末态哈希。旧插件会拒绝大于500步的新文件，回退前须保留新数据，不能篡改 maxTicks 强行读取。Paper 同时验证外层 owner 与内层 worldId，替换 owner 字段不能转移生态舱归属。

P7 沿用 `ArenaRuleset` schemaVersion=1，不增添可执行字段。玩家草稿位于 `arenas/drafts/<author UUID>/<sha256(id)>.json`，公开不可变版本位于 `arenas/published/<sha256(id@version)>.json`。同一 ID/version 一经发布不覆盖，变更必须提升语义版本。依赖以精确 DesignId/version/hash 固定，加载/发布时解析并校验公开与复用许可。发布文件未知字段、hash错配或依赖授权失效时拒绝加载和引用。

P8 `teams.json` 以 schemaVersion=1 原子保存赛前 UUID 双队 roster，限制8个玩家、每队至多4人。历史 ArenaMatch replay schemaVersion=1 固定引擎 `genesis-arena-match/1`；读入和继续运行保持原格式、match ID 派生方式及 state/event commitments，不补写 runId。固定回归样本 `stargenesis-paper/src/test/resources/arena-match-v1.json` 从 `31ecf9c` 编译产物导出，包含已完成一轮、一个 pending action 和 observer。

新比赛使用 replay schemaVersion=2 / `genesis-arena-match/2`，增加必填规范 UUID 字符串 `runId`。match ID 同时固定 runId、ruleset hash 和 roster，允许同一 seed/规则/队伍重复开赛而不复用事件 EvidenceId。回放器按明确版本构造引擎；未知或不匹配的 schema/engine、v2 缺少 runId、v1 混入 runId、身份或哈希被修改均拒绝。模拟 Java API 保留原 String matchId 构造函数供 v1 消费者使用；新比赛使用 UUID runId 构造函数。ArenaMatchReplay 保留旧构造函数，v2 使用新增 runId 的完整构造函数。

Paper 外层 match session 仍为 schemaVersion=1，同时保存 replay、尚未凑齐的一轮动作和 observer roster。单文件上限8 MiB、目录128场且合计64 MiB，写盘与扫描均检查预算，强制落盘后原子替换。历史不会自动删除；OP 使用 `match prune <matchId>` 只清理已结束且无 pending 的记录。恢复时再次解析 ruleset 发布版及许可闭包，并校验尺寸、时长、unitBudget 与 pinned document 一致。未知字段、规则被撤销、roster 不匹配或 replay commitment 变化均拒绝恢复。赛前 roster 与比赛历史分开恢复；历史不重新加入已退出的玩家。显式查看选择仅保留在当前连接，重连/重启优先选择活跃参赛，其次活跃观战，最后已结束记录；同级按 match ID 排序。旧错误 pending 可由提交者使用 `withdraw` 撤回，不能重写已结算回合。

P9 saga file schemaVersion=1 按 saga UUID 保存请求、授权 permit、状态和 revision，文件上限64 KiB并原子替换。付款/物品操作 key 根据 saga UUID 与 phase 稳定派生。恢复时先 lookup 外部 key；UNKNOWN 或副作用调用不确定时不会自动重试，并进入人工复核或保持可查状态。真实结算 adapter 尚未注册，StarCore/Vault 交易保持禁用。

P10 signed design envelope 固定 envelopeVersion=1、Ed25519、publisher keyId、规范 design JSON 和 signature。Paper 导入包按玩家 UUID 与 DesignId hash 保存，读取时重新验证 trust root、签名、hash 和 schema。未受信 key、未知 envelope/schema 或签名不匹配均拒绝，不自动执行或迁移。OP 可通过 `/genesis design publishers add` 从 `publisher-inbox` 显式加入 X.509 PEM 公钥，并用 `list` 检查 SHA-256 指纹。`revoke` 会先原子写入带原因和时间的不可覆盖撤销记录，再清理活动公钥文件；加载时撤销记录优先，keyId 不能复用。轮换先加入新 key，再验证新 key 签名包，最后撤销旧 key。撤销后，之后的读取会重新验证并拒绝仅由旧 key 签名的已导入包。历史 `.pub` 信任文件继续兼容，控制台/OP 操作不读取或保存 publisher 私钥。

P11 `mvnw.cmd -B verify` 运行 CycloneDX Maven Plugin 2.9.1 aggregate goal，输出 CycloneDX 1.6 JSON 到 `target/sbom.json`。该 SBOM 是本构建的组件清单，不等价于第三方源码审计、签名发行或运行时兼容验收。

## 后续变更流程

先新增 schemaVersion 与迁移测试，再修改写出版本；保留旧输入回归 fixture，拒绝未知字段和未来版本。破坏确定性的引擎更新必须提升 engine version，旧回放交给对应版本验证器或明确报告不支持。公开 API 改签名须说明消费者、线程边界、预算、迁移与合同测试。
