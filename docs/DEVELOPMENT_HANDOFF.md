# StarGenesis 开发交接 · 2026-11-25

当前版本为 **4.0.0-SNAPSHOT**，代码同步目标为 `addxiaoyi/StarGenesis` 的 `main`。P0 基础验收已通过；P1–P8 已有主要实现及不同程度的自动化验证；P9、P10、P11 仍有生产接入或阶段验收缺口。当前没有正式稳定版发布。

最新工作是 P7 审核者签名、审核历史查询及显式发布恢复。换设备后先阅读本文和 [PHASE_STATUS.md](PHASE_STATUS.md)，再接着完成下列优先事项。本文是 2026-11-25 的交接快照；后续变更继续更新阶段状态和实际证据。

## 阶段进度

| 阶段 | 已实现与已验证 | 剩余工作 |
|---|---|---|
| P0 基础 | 五模块、设计格式、API、配置权限与上游源码审计；基础实服启停通过 | 本阶段基础验收已通过 |
| P1 实验室 | 确定性网格、动作、检查点、回放、玩家 Inventory | 真人流程与 PDC 保存周期耐久性 |
| P2 蓝图 | 编辑、连接、预算、撤销、权限、私有虚拟装配与进度保存 | 真人操作体验、保存恢复验收 |
| P3 程序 | 25 个 typed IR 操作、编译、有界解释器、编辑菜单、跟踪和实验室运行 | 真人可用性与实服玩家验收 |
| P4 通信 | 协议芯片、同服消息、HMAC peer relay、持久 outbox 与去重；Paper/Folia 代理流程各 12 项通过 | 真人多玩家与崩溃保存耐久性 |
| P5 生态 | 8×8 空间生态、物质/能量守恒、复合反应、v4 迁移、共享组；100,000 步恢复测试及两端各 28 项离线客户端检查通过 | 真人协作、长期与异常保存验收 |
| P6 工厂 | 四人名册、订单竞价交付、生产工位、唯一账本、远程成员命令；72,000 tick 恢复与两端代理各 11 项检查通过 | 真人四人体验、断电及提交边界故障验证 |
| P7 竞技规则 | schema-2 地图、胜利条件、预算、ACL、不可变发布、Ed25519 审核、历史查询、显式恢复 | 已签名批准的成功恢复实服完整流程、真人审核、内容策略与身份核验 |
| P8 多人竞技 | 固定规则版本、UUID 席位、锁步回合、超时、只读观战、原子检查点；Paper/Folia 离线客户端流程通过 | 真人体验、反作弊、跨服、长期故障测试 |
| P9 结算 | 有界 saga、分步幂等键、逆序补偿、UNKNOWN 复核、持久日志；nation-resource bridge 合同测试 | 稳定上游依赖、生产 adapter、授权、人工恢复与实服故障验收；真实结算关闭 |
| P10 交换与扩展 | Ed25519 `.sgdesign`、作者信任表、签名归档、按精确版本和 JAR 哈希授权的扩展 SDK | 生产密钥管理、第三方互操作、真人验收；扩展默认关闭 |
| P11 工程与发布 | Java 21/25 构建、性能采样脚本、CI、SBOM 与发布流程 | Paper/Folia 各 30 分钟性能门、正式发布及真人留存数据 |

阶段中的“离线客户端”指自动化 Minecraft 客户端，不等同于真人验收；模拟 provider 测试不等同于真实经济适配器。

## 最近完成的代码

- 审核签名绑定候选 SHA-256、reviewer UUID、结果和准确拒绝理由；决议保留公钥、指纹和签名。新决议必须有受信 Ed25519 签名；旧 v1/v2 决议保留原审核者恢复路径。
- `/genesis arena decisions [page]` 每页读取 20 条决议；`decision <sha256>` 展示签名及 `PUBLISHED`、`APPROVED_AWAITING_PUBLICATION`、`NOT_PUBLISHED` 状态。查询本身不执行恢复。
- `/genesis arena recover <sha256> <reviewer-uuid> <original-signature>` 可由授权玩家或控制台调用，只重放已有的签名批准。恢复前复核候选来源、当前编辑 ACL、版本和依赖；写入失败保留候选，成功重试不重复发布。
- 修复 240 字中文拒绝理由写入后无法重读，决议读写上限统一为 2 KiB；Java/Node 拒绝非法 Unicode，并共用多语言签名向量。Node `verify` 模式要求预先核对的公钥指纹。
- `server_smoke.py` 增加审核查询和恢复拒绝路径检查，并修复 Windows 下 263 字符发布文件路径造成的误报。

关键实现：`ArenaReviewQueue`、`ArenaReviewCommands`、`ArenaReviewRecoveryCommands`、`PublishedDesignStore`、`StarGenesisPlugin`；签名工具为 `scripts/arena_review_sign.mjs`。命令和信任语义见 [ARENA_RULESETS.md](ARENA_RULESETS.md) 与 [DESIGN_EXCHANGE.md](DESIGN_EXCHANGE.md)。

## 最近验证结果

| 验证 | 结果 | 范围 |
|---|---|---|
| Java 21 `clean verify` | 392 项，0 失败/错误/跳过 | 全仓测试与插件打包 |
| Java 25 `clean verify` | 392 项，0 失败/错误/跳过 | 全仓测试与插件打包，类文件目标 Java 21 |
| 审核/发布定向测试 | 39 项通过 | 成功恢复、重复恢复、撤销信任、权限/依赖/来源复核、非法恢复拒绝、写入失败重试 |
| Node 脚本测试 | 36 项通过 | benchmark、代理 helper、审核签名工具 |
| Paper 1.21.11-132 | 31 项控制台检查通过 | 插件生命周期、查询、恢复用法、拒绝新建批准 |
| Folia 1.21.11-14 | 31 项控制台检查通过 | 同上 |

最新摘要与构件哈希：[review-recovery-20261125.json](evidence/p7/review-recovery-20261125.json)。换设备可读取的原始日志与哈希索引见 [handoff-log-index-20261125.json](evidence/p7/handoff-log-index-20261125.json)。此前阶段证据保留在 `docs/evidence/`；失败记录也保留。

最后构建的插件 SHA-256（Java 25 构建、Java 21 目标）：`84e67dd2c014390f85c64cba5e6a579608de93226415d7313958e53817a90ab1`。JAR 位于本机 `stargenesis-paper/target/`，新设备应重新构建；它不是已发布的稳定版本。

## 新设备继续开发

安装 Git、JDK 21（另用 JDK 25 验证矩阵）、Python 3.12、Node 22。设置 `JAVA_HOME` 并把 Java 加入 PATH。Maven Wrapper 会下载固定 Maven 3.9.11；纯 Java/Node 单元测试不需要 Minecraft 服务器或 Mineflayer。

```sh
git clone https://github.com/addxiaoyi/StarGenesis.git
cd StarGenesis
git switch main
git pull --ff-only
python scripts/check_boundaries.py
sh mvnw -B -ntp clean verify
node --test scripts/server_benchmark.test.mjs scripts/protocol_proxy_smoke_helpers.test.mjs scripts/arena_review_sign.test.mjs
```

Windows 将 Maven 命令改成 `mvnw.cmd -B -ntp clean verify`。上次 Windows 主机内存紧张时使用以下参数完成两套 JDK 的验证：

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2'
.\mvnw.cmd -T1 '-DargLine=-Xmx192m -XX:ActiveProcessorCount=2' clean verify
```

插件产物为 `stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar`。安装 shaded JAR；`original-*` 和各库模块 JAR 不作为独立插件安装。

`.work/`、本机 JDK、服务器 JAR/世界、代理、Mineflayer 依赖、私钥、运行时信任文件和 `target/` 不通过 Git 迁移。需要实服测试时按 README 与各 engine 文档重新准备隔离目录；只在已接受 EULA 的前提下启服。自动客户端脚本使用 `.work/arena-e2e` 中的 Mineflayer，benchmark 另支持客户端模块目录参数。旧证据中的绝对路径属于原 Windows 主机，不能直接当作新设备路径使用。

## 下一步优先事项

1. 完成 P7 成功恢复的真实 Paper/Folia 流程：在隔离服持久化合法签名批准并保留候选，模拟发布未完成，重启后通过控制台 `recover`；核对原决议字节不变、候选清理、唯一发布、重复调用幂等，并覆盖错误 reviewer/签名、ACL 撤销和缺失依赖。当前成功恢复已有代码测试，不能据此宣称实服成功恢复完成。
2. 在资源充足、无其他 Java 工作负载的主机分别运行 P11 30 分钟门。两端都要满足 TPS P50≥19、tick P95≤50 ms、可用内存≥1,024 MiB、RSS≤1,536 MiB、heap≤480 MiB、五类指标样本覆盖率≥80%；不能用短测或失败记录代替。见 [P11_BENCHMARKS.md](P11_BENCHMARKS.md)。
3. 按 P1–P8 的缺口补真人多人可用性和异常保存流程；若继续 P9，先固定已发布上游 API，再实现生产 adapter 与授权/处置流程。此前 StarCore nation-resource 候选 API 不是稳定依赖，不应直接接入生产。

继续保持 `integrations.allow-real-settlement: false`；模拟资源不兑付真实物品或货币。保留历史 codec/决议读取兼容、固定版本/哈希依赖、原子保存与去重记录。每次有行为变更时完成相应测试与正式 verify，再更新阶段证据。

可在新设备直接交给 Codex：

> 继续 StarGenesis 代码开发。先读 docs/DEVELOPMENT_HANDOFF.md、docs/PHASE_STATUS.md 和 docs/PRODUCT_CONTRACT.md，确认 main 最新代码与 Git 状态。优先完成 P7 已持久化签名批准的 Paper/Folia 实服成功恢复流程，补可复现自动化和证据；不要把单测、控制台拒绝路径或离线客户端当作真人验收。真实结算保持关闭。
