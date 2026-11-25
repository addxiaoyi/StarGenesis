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

P1 新生命周期将快照及其完整历史放入单个 `lab/<owner UUID>.lab.json`，外层 schemaVersion=1 固定 owner，内层继续使用 `genesis-sandbox/1` / snapshot schemaVersion=1；回放从同一历史派生。文件上限仍为4 MiB，强制落盘后原子替换，失败不得只更新快照或回放之一。若合并文件不存在，读取旧 `.snapshot.json` / `.replay.json`；存在但损坏、归属不符或版本未知时明确拒绝，不退回旧记录。首次成功保存合并文件后优先读取它，原旧文件原字节保留。回退旧插件会重新读到旧文件，因此回退前须保留最新合并文件，不应把旧进度误当成当前进度。

新实验先提交第0步，之后每100步、停止、自然结束及关闭保存；失败进入 SAVING 并冻结待保存状态，下一轮重试。首次提交失败可取消且不会替代上一局；后续停止保存失败仍占用活跃实验席位。最终关服保存失败会记录错误并保留上一有效文件，不保证未保存步骤。回放始终只读；终局检查点恢复不会再推进。检查点恢复按历史输入重演并逐帧核对状态哈希、事件及最终完整状态，矛盾旧存档现在拒绝；合法旧档保留原哈希。固定 snapshot/replay fixture 从 `556a5e3` 的已验证JAR导出，验证第50步到第100步的原哈希。新场次ID使用随机UUID，重复种子与重启不会复用旧实验事件身份；恢复继续使用已存ID。

P3 typed IR 使用独立 `TYPED_PROGRAM` schemaVersion=1，不改变原 schema-v1 Program 文档；旧 Program 只通过显式 `ProgramCompiler` 编译。typed-program 文档使用规范 JSON SHA-256，未知 schema、原语、字段或类型拒绝加载。Paper 存储按作者 UUID 隔离，每玩家64份，显式保存并检查打开时的基准哈希。当前尚无 schema v0 或兼容性迁移。

P3 预算修复继续使用同一typed-program schema与内容hash。执行时以 `min(program.maxInstructionsPerStep, host.nodesPerAgentStep)` 创建受限执行视图，保存文档的声明预算不改写；因此降低主机预算后仍可打开旧程序，但执行可能报告 BUDGET_EXCEEDED。逐指令step/continue共用同次执行额度，编辑或上下文变化则明确开始新执行。短时simulate使用完整主机SimulationBudget，最多20步且不越过主机maxSteps；任何参与agent的程序/消息/动作验证失败都不提交该tick的输入或信号。每次命令模拟使用独立UUID场次，已有世界回放算法与schema不变。

保存使用强制落盘后原子替换；失败不清除编辑器草稿、撤销历史或打开时基准hash。load/list同时拒绝作者不符与ID文件名不符，矛盾文件不自动改名。阈值比较operator只允许eq/lt/gt；以前可保存但运行必报错的未知值现在在编辑/加载阶段拒绝。固定 `program-v1-host-budget.json` 从 `606286f` 的验证JAR导出，验证声明256节点的旧程序在较低主机预算下仍保留原内容hash。

P4 radio wire message 引用已封存 Protocol ID 与 SHA-256，不引入 wire message 格式变化。PDC chip 记录 protocol ID/hash，不构成数字签名或身份授权。Paper 将 mailbox、发送/确认事件、sequence 去重、ContractRuntime 状态以及当前 Protocol/Contract ID 与 hash 写入 `radio-state.json` schemaVersion=1，文件上限16 MiB并通过原子替换更新。入队、确认、成功处理的 Contract 消息和关服时立即保存，逻辑 tick 每20步检查点保存。未知 schema、pinned hash 变化、tick 不一致或超预算时拒绝恢复，不自动丢弃或迁移数据。在线端点不落盘，重启后玩家须重新 join。当前固定 service Contract 定义变化需要后续显式迁移，不能直接替换后复用旧快照。

P4 稳定性续开发仍使用同一schema-v1和固定协议/契约定义。发送、ack和契约收件在隔离候选状态中执行，原子保存成功才替换当前状态；失败保留原内存、消息额度、在线端点和文件，可按原幂等键重试。收件批次的成功契约处理与ack一起提交，非法消息继续保留；失败批次不会在关服时写入。契约超时必须先成功保存，失败时两边时钟均不前移；普通时钟仍每20步检查点，失败检查点可自动重试。恢复从当前tick的发送证据重建已用预算，降低配置额度后也不会清零历史用量。

ContractRuntime恢复按实际消息、处理tick和deadline重演全部事件，核对最终状态和去重证据；处理巨大空闲tick时跳过无事件区间，不逐tick循环。Paper另核对每条已应用契约消息确有相同的radio消息与ack。旧版本曾接受的矛盾状态现在明确拒绝，不静默修补。固定 `stargenesis-paper/src/test/resources/radio-v1-pending-fulfillment.json` 从 `4411675` 的已验证JAR导出，含OFFERED/ACCEPTED历史和一个待投递fulfill；新实现可按原消息继续部分及最终履约，文件格式和固定设计hash不变。

P5 ecology replay 使用 schemaVersion=1 和固定 `genesis-ecology/1` 引擎版本，包含玩家所有权、world ID、seed、配置、初态哈希、每步输入、状态哈希和事件哈希。Paper 按 UUID 存储，文件上限4 MiB并原子替换。读取时拒绝未知字段、所有权变化、超预算文件、未知引擎和任何重演哈希差异。变更生态算法需提升引擎版本，不能静默重算旧 replay。

P5 可选时长将已存在的 config.maxTicks 允许范围扩到1–1536，默认500与默认50,000次账本证据保持不变。新长局按 `max(50,000, ticks * 32 * 2)` 预留证据，最大98,304，不改变账本100,000、个体32、代数8和默认出生512的硬预算。格式与每步算法均未改变，因此仍为 schemaVersion=1 / `genesis-ecology/1`；旧文件按原配置原哈希恢复，不升级时长。固定样本 `stargenesis-paper/src/test/resources/ecology-v1-default.json` 从 `9be9cb5` 的构建产物导出并校验原初态/末态哈希。旧插件会拒绝大于500步的新文件，回退前须保留新数据，不能篡改 maxTicks 强行读取。Paper 同时验证外层 owner 与内层 worldId，替换 owner 字段不能转移生态舱归属。

P5 长周期候选研究只新增搜索脚本、可选研究profile与固定恢复/灭绝回归，不修改 `genesis-ecology/1`、默认程序、配置、历史输入或commitments。旧研究报告保留原三组/四组结果，当前脚本包含六组且默认显式使用v1。

P5后续生命周期升级使用`genesis-ecology/2`，文件外层和replay仍为schemaVersion=1。新建舱改用有成熟期、繁殖间隔和摄入储量上限的新规则及长寿命默认程序；world ID继续绑定证据和所有权，但不再参与处理优先级、猎物选择或突变选择。v2状态hash新增个体ordinal和lastBirthAge，故不能修改engineVersion强行转换旧档。恢复与验证支持两个版本，v1继续原排序、默认程序、算法和哈希；读取或继续旧舱不升级。旧插件明确拒绝v2，回退前须备份并使用对应引擎读取，不截断历史、不重算commitments。新旧文件仍按4 MiB原子保存，失败保留上一份成功证据。详细语义与默认参数见`ECOLOGY_ENGINE_V2.md`。

P6 工厂旧 replay schemaVersion=1 / `genesis-factory/1` 继续按原有序四人 roster 派生 match ID，恢复和推进保留原 commitments，不补写 runId。固定样本 `stargenesis-paper/src/test/resources/factory-v1-pending-order.json` 从 `b7f9e4d` 编译产物导出，保存到订单已预留但尚未补偿的第5 tick；测试核对原初态、第5 tick和第6 tick补偿后哈希。

P6 新比赛使用 replay schemaVersion=2 / `genesis-factory/2` 与必填规范 UUID `runId`，match ID 同时固定场次和有序 roster，允许同队伍/seed 重复比赛；事件身份随场次变化，经济/故障算法不变。Java API 保留旧 String matchId 和八参数 replay 构造函数；新场次使用 UUID 构造函数。读取拒绝版本错配、v2 缺失 runId、v1 混入 runId、文件身份错误和事件或哈希篡改。旧插件不能读取 v2，回退前须保留新场次文件。

P6 外层 match 文件与 `organizations.json` 仍为 schemaVersion=1。单场8 MiB，目录256场/64 MiB，写盘前核对预算并强制落盘后原子替换。当前组织与历史分开恢复，重连/重启优先选择活跃比赛，同级按 match ID 排序。退出组织须双方均无活跃比赛，不能通过选择历史绕过限制；未完成邀请不落盘，断线取消邀请。旧 `factory reset` 不再删除共享活跃比赛，改为具备 `stargenesis.factory.manage`（默认 OP）才能调用的已完成历史清理别名；管理员也必须等所有订单完成或补偿后才能用 `prune <matchId>` / `reset [matchId]` 清理。

P7 沿用 `ArenaRuleset` schemaVersion=1，不增添可执行字段。玩家草稿位于 `arenas/drafts/<author UUID>/<sha256(id)>.json`，公开不可变版本位于 `arenas/published/<sha256(id@version)>.json`。同一 ID/version 一经发布不覆盖，变更必须提升语义版本。依赖以精确 DesignId/version/hash 固定，加载/发布时解析并校验公开与复用许可。发布文件未知字段、hash错配或依赖授权失效时拒绝加载和引用。

P7 稳定性修复不改变ruleset和review schema-v1。新clone使用 `clone:<author>:<id>@<version>#<hash>` 来源，旧clone的较短source保持原文，不自动补写导致hash变化。草稿读取/列表、发布目录和审核记录同时核对路径与文档身份；审核作者必须与文档作者相同。`null`审核文件返回明确的证据错误。已提交（包括被拒绝）或发布的ID保留原作者，禁止其他作者提交新版；内置starter ID不允许新UGC覆盖。此前已有的合法发布继续可读，不自动删除或重新归属历史文件。

依赖解析缓存每个精确pin的验证结果和最长剩余深度，避免共享DAG反复展开；缓存仅存于本次校验，后续调用重新读取当前registry和许可。全部设计类型均检查递归依赖，仍保持16层和公开可复用许可要求。草稿新作者在写目录前执行64人准入；读取发布目录计数包含被审核状态隐藏的文件。审核/发布仍是两个原子文件：APPROVED决定可先于发布文件存在，发布失败由原审核员重试；不伪称两个文件具备单次原子提交。固定 `arena-v1-published.json` / `arena-v1-reviews.json` 从 `7021845` 的验证JAR导出，保留原短来源和内容hash，在新实现中读取与重复approve不改写原字节。

P8 `teams.json` 以 schemaVersion=1 原子保存赛前 UUID 双队 roster，限制8个玩家、每队至多4人。历史 ArenaMatch replay schemaVersion=1 固定引擎 `genesis-arena-match/1`；读入和继续运行保持原格式、match ID 派生方式及 state/event commitments，不补写 runId。固定回归样本 `stargenesis-paper/src/test/resources/arena-match-v1.json` 从 `31ecf9c` 编译产物导出，包含已完成一轮、一个 pending action 和 observer。

新比赛使用 replay schemaVersion=2 / `genesis-arena-match/2`，增加必填规范 UUID 字符串 `runId`。match ID 同时固定 runId、ruleset hash 和 roster，允许同一 seed/规则/队伍重复开赛而不复用事件 EvidenceId。回放器按明确版本构造引擎；未知或不匹配的 schema/engine、v2 缺少 runId、v1 混入 runId、身份或哈希被修改均拒绝。模拟 Java API 保留原 String matchId 构造函数供 v1 消费者使用；新比赛使用 UUID runId 构造函数。ArenaMatchReplay 保留旧构造函数，v2 使用新增 runId 的完整构造函数。

Paper 外层 match session 仍为 schemaVersion=1，同时保存 replay、尚未凑齐的一轮动作和 observer roster。单文件上限8 MiB、目录128场且合计64 MiB，写盘与扫描均检查预算，强制落盘后原子替换。历史不会自动删除；OP 使用 `match prune <matchId>` 只清理已结束且无 pending 的记录。恢复时再次解析 ruleset 发布版及许可闭包，并校验尺寸、时长、unitBudget 与 pinned document 一致。未知字段、规则被撤销、roster 不匹配或 replay commitment 变化均拒绝恢复。赛前 roster 与比赛历史分开恢复；历史不重新加入已退出的玩家。显式查看选择仅保留在当前连接，重连/重启优先选择活跃参赛，其次活跃观战，最后已结束记录；同级按 match ID 排序。旧错误 pending 可由提交者使用 `withdraw` 撤回，不能重写已结算回合。

P8 长局准入不改变文件 schema 或模拟 commitments。Paper 新建入口按固定v1/v2 JSON格式计算保守上界：每位玩家至多一个目标、每目标至多contest与resolution两条事件，两队各一条spend/regen，另预留settlement，以及全部pending输入和128名observer。按最长团队名、整数长度和最后一轮tick序号计算序列化大小；超过8 MiB在写文件前拒绝，并提示可准入轮数。默认1200轮4–8人仍可开始；设计允许的6000轮不代表当前存储可以承载。目录为每个活跃比赛预留其最终上界，完成历史按当前大小加8 KiB观战/输入余量计入，重启从存档重算，不增加新元数据。旧超长局保留读写兼容并计入完整8 MiB预留，其本身依然可能触及旧文件上限；不自动截短或迁移旧比赛。更新引擎事件格式时必须同时更新容量上界与回归。

P9 saga file schemaVersion=1 按 saga UUID 保存请求、授权 permit、状态和 revision，文件上限64 KiB并原子替换。付款/物品操作 key 根据 saga UUID 与 phase 稳定派生。恢复时先 lookup 外部 key；UNKNOWN 或副作用调用不确定时不会自动重试，并进入人工复核或保持可查状态。真实结算 adapter 尚未注册，StarCore/Vault 交易保持禁用。

P9 显式复核引入外层schemaVersion=2，内层SettlementSaga仍为schemaVersion=1，原operation key和请求/permit不变。普通新建与从未复核的旧文件继续写v1；只读加载不改写。首次成功`commitReview`才将前后snapshot、身份/授权和三项查询证据与新状态一起原子写为v2，后续自动推进保留全部记录。未知外层/记录版本、空或超过16条历史、重复review ID、乱序revision/tick、错误状态组合、参与者自审、被抹除的APPLIED证据均拒绝；64 KiB实际字节上限仍执行。旧插件不理解v2，回退前须保留已复核文件并使用支持它的版本恢复，禁止删掉审计字段伪装成v1。

P9 并发恢复修复不改变文件格式、operation key 或合法旧状态。新增 `SettlementSagaRepository.withExclusiveSaga(UUID, Supplier<T>)` 合同要求跨仓库实例、跨共享存储进程互斥，作用域覆盖整个同步推进；同一saga的嵌套读写可重入，嵌套不同saga明确拒绝。旧repository实现继承的默认方法会在授权/外部调用前抛出UnsupportedOperationException，必须实现新的排他范围后才能用于coordinator。Paper复用按UUID分片的JVM锁与OS文件锁，移除会阻塞其他saga的实例级锁，并在异常时释放作用域。持锁期间外部adapter须自行提供有限超时，不能把异步副作用逃逸到回调结束之后。

新建只能写PREPARED/revision=0/空reason，替换必须保留原request/permit、递增一版并遵循现有状态转移；不允许普通replace重开MANUAL_REVIEW或其他终局。重复begin的同请求会先返回已存记录，过期只阻止新建；负tick与ID复用为不同请求仍拒绝。旧v1已付款fixture从`a0cf186`的已验证JAR导出，读取不改原字节，并可在过期后继续补偿。人工复核仍无生产管理入口，不能通过直接改JSON绕过授权或外部结果核对。

P10 signed design envelope 固定 envelopeVersion=1、Ed25519、publisher keyId、规范 design JSON 和 signature。Paper 导入包按玩家 UUID 与 DesignId hash 保存，读取时重新验证 trust root、签名、hash 和 schema。未受信 key、未知 envelope/schema 或签名不匹配均拒绝，不自动执行或迁移。OP 可通过 `/genesis design publishers add` 从 `publisher-inbox` 显式加入 X.509 PEM 公钥，并用 `list` 检查 SHA-256 指纹。`revoke` 会先原子写入带原因和时间的不可覆盖撤销记录，再清理活动公钥文件；加载时撤销记录优先，keyId 不能复用。轮换先加入新 key，再验证新 key 签名包，最后撤销旧 key。撤销后，之后的读取会重新验证并拒绝仅由旧 key 签名的已导入包。历史 `.pub` 信任文件继续兼容，控制台/OP 操作不读取或保存 publisher 私钥。

P10 信任库稳定性修复保持PEM、revocation schemaVersion=1和签名格式不变。新读写都通过目录内 `.publisher.lock`，使用JVM锁与OS文件锁覆盖完整检查/写入；该目录及锁文件须可写。新keyId须满足active+revoked<4096后才可加入，为每个活跃key预留将来的撤销记录；原32活跃/4096撤销上限不增加。重复添加同一key仍幂等。旧库即使已超过新的总身份准入条件也保持读兼容，但4096条撤销记录写满时会在新增撤销前拒绝，避免写出自身无法再读的第4097条记录。历史容量迁移仍待实现，不自动删记录或重用撤销keyId。

撤销JSON现在拒绝小数/字符串schema及数字/布尔值冒充文本，读取限制4 KiB并再次检查实际读取字节数；原合法v1记录不重写。固定 `publisher-v1-active.pub` / `publisher-v1-revoked.json` 从`f7564af`已验证JAR导出，重复add/revoke保留原公钥、撤销时间、原因与原字节。持久写入失败保留原信任状态；若撤销记录已提交但清理未完成，加载立即按撤销处理，重试只补清理，不覆盖原证据。

P11 `mvnw.cmd -B verify` 运行 CycloneDX Maven Plugin 2.9.1 aggregate goal，输出 CycloneDX 1.6 JSON 到 `target/sbom.json`。该 SBOM 是本构建的组件清单，不等价于第三方源码审计、签名发行或运行时兼容验收。

## 后续变更流程

先新增 schemaVersion 与迁移测试，再修改写出版本；保留旧输入回归 fixture，拒绝未知字段和未来版本。破坏确定性的引擎更新必须提升 engine version，旧回放交给对应版本验证器或明确报告不支持。公开 API 改签名须说明消费者、线程边界、预算、迁移与合同测试。
