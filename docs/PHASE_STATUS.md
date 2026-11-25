# 阶段状态

更新时间：2026-11-26。状态以当前代码和可复现证据为准。未执行或证据不足的阶段门保持未通过。

## 差异审计

远端 main 初始提交 `8b7489346721ad4127f87f95d8e89775d218c74a` 只有 README 和 MIT LICENSE。本地建立五个有实际代码的 Maven 模块。StarCore、helsteraMobs 与 SenLuo/CraftEngine 按固定源码 SHA 审计，详见 `INTEGRATION_AUDIT.md` 和 `docs/audits/`。第三方源码、插件安装或模拟 adapter 均不等于实服兼容。

## 当前阶段

| 阶段 | 状态 | 已有实现与未完成验收 |
|---|---|---|
| P0 | 已通过基础验收 | 五模块、设计格式/API/权限基线已落地；88项测试、全新 Maven 缓存 verify 和 Paper/Folia 1.21.11-132/14 控制台启停通过，见 `docs/evidence/p0/`。这不包含第三方功能或真人玩法验收 |
| P1 | 实现已落地，阶段门未通过 | 既有 Paper/Folia 客户端覆盖启动、停止、回放和恢复；新增单文件原子保存、失败冻结重试与完整历史校验已通过单元回归。新生命周期尚无实服验证，仍缺真人交互验收 |
| P2 | 实现已落地，阶段门未通过 | Paper/Folia 客户端创建、编辑、预览、保存并重开个人蓝图。仍缺真人编辑体验和长期重启/隔离验收 |
| P3 | 实现已落地，阶段门未通过 | typed program 编辑、沙盒执行和 replay 已覆盖。仍缺真人程序工作流和多人性能验收 |
| P4 | 实现已落地，阶段门未通过 | Paper/Folia 实服流程验证 at-least-once poll、ack、收件人断线重投和跨 tick 去重。仍缺真人通信体验与长期网络负载验收 |
| P5 | 实现已落地，阶段门未通过 | 四组实服对照 profile 均会灭绝。固定候选 profile 曾在 Paper/Folia SNAPSHOT/RC 和16次实服 seed 扩展中到达500 tick并保留三物种，另有200次固定候选和400次多参数纯模拟500步筛选。新增1–1536步有界实验与24个纯模拟长期对照：原候选8/8在512步失去食草者，三组参数均未保持三物种至1536步；后续128组搜索的新候选在64组扩展中只有40组保留三物种。长期平衡、实服长局和真人验收仍未完成 |
| P6 | 实现已落地，阶段门未通过 | Paper/Folia 既有客户端流程验证 compensation、退款、escrow 释放和 replay；新增独立场次、退出重组、活跃优先恢复、管理员终局清理及写盘失败回归。新增生命周期命令尚无实服验证，仍缺四名真人玩家故障比赛验收 |
| P7 | 代码和自动审核流程已落地，阶段门未通过 | 待审不可见、作者不能自审、独立 OP 批准/拒绝、拒绝理由和版本隔离已有实服覆盖。仍缺真人创作体验、运营治理和反作弊比赛验收 |
| P8 | 实现已落地，阶段门未通过 | Paper/Folia 验证四人回合、只读 observer、replay 以及 TCP 硬断线后 pending action/match ID/UUID 恢复。仍缺真人双队比赛、公平性和竞技性能验收 |
| P9 | 部分实现，阶段门未通过 | 已有整笔推进排他锁、持久 saga、不可变request/permit、补偿和manual review；并发过期与真实写盘失败恢复有回归。没有通过外部幂等/权限/故障审计的真实经济/库存adapter，生产结算保持关闭 |
| P10 | 签署/导入和 trust-root 生命周期已落地，阶段门未通过 | 有 Ed25519 包签署/验证、OP 公钥添加/轮换/撤销和私有导入。仍缺运营方真实 trust root、真实第三方 bridge 和第三方验收 |
| P11 | 部分实现，阶段门未通过 | 有 CycloneDX 1.6 SBOM、scale probe、双平台自动流程、15分钟受控 Folia 沙盒负载、可重复 RC 构建。仍缺生产规模容量研究、真人验收/留存研究、最终正式发行签名 |

## P1–P3

P1 在纯 Java 模块实现确定性地形、寻路、采集、投递、能源预算、checkpoint 和 replay。Paper 提供虚拟库存投影，Folia 使用命令式工作流且不创建玩家背包界面。每次合并运行测试和边界扫描。自动客户端不是真人验收。

P1 稳定性续开发将实验生命周期从控制器提取为可单测的 LabExperiment。快照及其回放历史在同一个4 MiB有界文件中原子保存，失败进入 SAVING、冻结待提交步骤并重试；首次保存失败可取消而不覆盖上一局，周期/停止/终局/关服失败保留有效检查点。只读回放不会替换证据。新场次使用独立UUID，旧 `.snapshot.json` / `.replay.json` 保留读兼容；从 `556a5e3` JAR导出的第50步固定样本可按原哈希续跑到第100步。恢复现在重演所有历史输入并核对事件与完整状态，先复现了旧实现接受篡改历史输入的问题。

该批次新增18项测试，Java 21/25 各通过353项（48个类），失败/错误/跳过均为0，边界扫描和双JVM发行/设计包签名自检通过。四造物默认2400步的固定样本可在4 MiB内保存、恢复与回放；这不是所有合法输入的容量证明。证据见 `docs/evidence/p1/lifecycle/summary.json`。本批次未重跑实服客户端或真人交互，异常退出仍可能回退至每100步保存的最后成功检查点，阶段门保持未通过。

P2 使用服务器模块白名单和有界物理代价进行蓝图评估。个人 workspace 支持编辑、预览、保存和撤销，文件按 UUID 与设计 ID 哈希隔离并用基准 hash 防止覆盖冲突。Paper/Folia 五客户端已创建、修改和重开蓝图。

无人值守续开发补齐 P2 共享编辑、公开只读、许可克隆、版本和许可撤销命令。授权从持久文档读取，协作者不能修改许可/版本/来源，旧会话在撤销后不能保存。作者 UUID 显式限定跨作者访问，克隆副本默认私有并记录原作者/ID/version/hash，保存使用强制落盘后原子替换。新增9个协作存储测试与1个工作区撤销测试，覆盖并发基准哈希冲突、重启、私有隔离、非法自授权及克隆来源；新命令尚未进行实服客户端验证。

该批次连同 P7 授权回归在 Java 21/25 各通过257项测试（36个测试类），失败/错误/跳过均为0；两个 JVM 的发行与设计包签名自检均通过。完整日志、工件哈希和验证边界见 `docs/evidence/p2/collaboration/summary.json`。全阶段剩余工程工作由 `docs/DEVELOPMENT_PLAN.md` 追踪。

P3 有35个封闭 typed primitive、IR schema/hash、解释器、断点/步进/撤销和有限资源沙盒。沙盒执行不访问 Minecraft world、真实库存或经济服务。自动客户端已编辑程序并验证 replay。

P3 稳定性续开发修复主机预算旁路：原sandbox只限制发出的世界动作，三条IR指令可绕过2节点host配置；现在调试器和sandbox均执行程序声明与主机节点预算的较小值，simulate同时使用主机时长、消息等配置，step/continue不重置额度。ProgramSession与ProgramSimulation从Bukkit控制器提取，单测覆盖编辑/撤销/上下文改变后旧执行失效、非法编辑保持原状态、断点清理、失败保存保留草稿和基准hash、并发冲突以及多agent输入整体拒绝。ProgramStore强制落盘后替换，并拒绝ID与文件名不符的合法文档；此错误也先通过失败回归复现。

本批新增18项测试并扩展非法比较operator回归。Java 21/25 各通过371项测试（50个类），失败/错误/跳过为0，模块边界与双JVM发行/设计包签名自检通过。旧typed-program schema-v1固定样本在较低host预算下保持原声明与hash。证据见 `docs/evidence/p3/lifecycle/summary.json`。simulate仍为每次最多20步的临时世界，保存的是程序文档，调试状态/信号不跨重启恢复；资源世界replay验证映射后的动作，不构成原typed程序来源证明。本批未新增实服/Folia多人或真人证据，阶段门未通过。

## P4–P5

P4 协议以固定 ID/hash 验证消息字段、recipient、tick/sequence 和 payload budget。消息至少一次投递，ack 前重复 poll 返回相同 envelope。Paper/Folia 实服客户端已验证重复 poll、ack 清空、recipient socket 断线重连后原消息重投，以及跨 tick 相同幂等键不重复投递。结果在 `docs/evidence/p11/paper-reliability-retest-workflow-result.json` 与 `folia-reliability-retest-workflow-result.json`。

P4 稳定性续开发将发送、ack和契约收件集中到可单测的RadioService。持久动作在隔离候选状态中执行，保存成功才发布；失败保留原消息、状态、端点和额度，关服不会补写已失败操作。成功处理的契约收件与ack在同一保存中提交，非法消息保留；契约超时立即保存，失败时两边时钟均保持前一tick，检查点/关服可以重试。恢复按当前tick消息证据重建额度，避免重启绕过每步上限。

契约恢复先逐事件重演消息和超时，再比对状态、事件顺序和去重记录；Paper还检查每条已应用消息都有相同radio证据和ack。修复前已分别用测试复现“重启清零消息额度”和“仅有offer历史却恢复为COMPLETED”。新增16项回归覆盖发送/确认/收件批次/履约/超时/关闭保存失败、断线重投、预算与邮箱恢复、历史篡改，以及从`4411675`导出的v1待履约样本继续完成。Java 21/25各通过335项测试（47个类），失败/错误/跳过均为0，模块边界和双JVM签名工具自检通过。证据见 `docs/evidence/p4/transaction-recovery/summary.json`。Java 21构建出现受限code-cache告警，不能作为性能结论；没有新增实服、真人或长期通信容量证据。

P5 以 `ConservingResourceLedger` 在 soil、detritus 和 organism biomass 间转移 matter。每步记录 state/event commitment 并支持 replay。自动实服四组 seed 52001–52004 对照 profile 在 Paper/Folia SNAPSHOT/RC 共八次运行中均灭绝。候选参数固定为 producer `(6,2,512,0)`、grazer `(1,12,512,0)`、decomposer `(1,12,512,0)`。seed 52008 在 SNAPSHOT 与 RC 的 Paper/Folia 共四次实服运行均达到500 tick，最终 population 为26/2/4、matter=1000，replay通过。

另基于两份从 Paper/Folia 实服回放读取的 Alice worldId，纯 Java 模拟扫描 seed 52008–52107。两个 worldId 各100/100次运行均保持三物种至500 tick，population 达到32，未发生 extinction。它是 simulation-only 结果，不是服务器实测，也不证明广泛参数空间或生态平衡。2026-11-25 在 Paper 1.21.11-132 与 Folia 1.21.11-14 各执行 seed 52008–52015 的客户端顺序扫描，16/16 运行达到500 tick，population 均为26/2/4、matter=1000，回放全部通过。Folia 五客户端分布于不同区域。结果见 `paper-seed-sweep-workflow-result.json` 和 `folia-seed-sweep-workflow-result.json`。这些固定规则的寿命上限为512，500 tick结果仍不足以证明长期生态平衡。原始实服样本见 `paper-ecology-study-retest-workflow-result.json` 和 `folia-ecology-study-retest-workflow-result.json`。

P5 续开发保留默认500步，新增 `ecology start <seed> <ticks>` 的1–1536步可选窗口。1536 × 32 × 2 = 98,304次转移证据，仍在原100,000次账本上限内；未增加人口、代数或出生预算。Dashboard显示已存配置；外层 owner 与内层 worldId 同时验证。12项新回归覆盖寿命终点物质回收、768步后恢复至1536步的一致性、旧存档固定哈希、500步后的篡改与写盘失败重试，以及最大输入回放在4 MiB以内的格式容量。Java 21/25 各通过290项测试（41个类），失败/错误/跳过为0，两个JVM的发行/设计包签名自检均通过。

`scripts/EcologyLongRunStudy.java` 对2个合成 worldId、seed 52008–52011和3组固定参数运行24个样本；每步物质守恒且24/24最终回放验证通过。所有样本在500步仍有三物种，但原候选8/8在512步失去grazer并以两物种到1536步；延迟繁殖组8/8在1065–1066步完全灭绝；错开寿命组6/8在1288步完全灭绝，另2/8到1536步时只剩分解者。没有样本证明三物种长期共存。原始报告、程序参数、首次物种灭绝时点、构建与签名结果见 `docs/evidence/p5/long-run/summary.json`。本批次没有新增实服或真人证据，长期平衡仍待开发。

2026-11-26 在不改变v1引擎、默认程序或预算的前提下新增 `EcologyBalanceSearch.java`：以固定搜索seed 20261125采样128组参数，只有候选63在搜索世界达到1536步保留三物种，并通过额外7组初步验证。候选参数为producer `(6,4,320,0)`、grazer `(4,24,512,0)`、decomposer `(3,4,320,0)`。随后在4个合成world ID与seed 52008–52023运行64组完整扩展，三物种存活数依次为15/16、15/16、0/16、10/16，共40/64；另24组在835–1475步间失去至少一个物种。22组失去producer，7组失去grazer，其中5组两者都失去。所有样本到达1536步、每步matter=1000、最终回放通过，出生147–153次、死亡122–128次。

新增两项回归分别固定世界0/1在768步恢复后逐步一致，以及世界2发生producer灭绝但守恒/回放仍正确的反例。Java 21/25 各通过391项测试（52个类），失败/错误/跳过为0，模块边界通过；生产源码未修改，同JDK的JAR/SBOM哈希与P7批次一致，因此沿用相同工件的签名自检证据。完整搜索、失败样本、脚本hash和构建日志见 `docs/evidence/p5/balance-search/summary.json`。新候选没有设为默认，8/8初步成功不能外推为跨world/seed稳健；未新增实服或真人证据，P5阶段门保持未通过。

## P6–P8

P6 由四名不同 UUID 玩家组成两个组织、竞价并下单。额外实服流程使用 roster 派生的确定 fault seed 触发部分生产失败，断言 `FAILED_RECOVERED`、recovered order、credits/ore 退回至预期余额、parts escrow 释放和 replay。结果见 `paper-factory-compensation-retest-workflow-result.json` 与 Folia 对应文件。这仍是五自动客户端，不替代真人比赛。

P6 续开发将组织/比赛操作集中到单线程服务，先写盘再发布结果。新场次使用 run UUID 的 replay v2，允许相同 roster/seed 重复比赛；v1 订单 fixture 按原哈希继续补偿。重连/重启优先恢复活跃比赛，选择历史不改变参赛占用；成员退出解散双人组织，历史恢复不重新加入旧成员。匹配跳过离线或忙碌组织，邀请不能相互覆盖，断线清理待处理邀请。`factory reset` 收紧为管理员清理已结束且无待补偿订单的历史别名，与新 `prune` 相同；普通成员不能删除共享比赛，OP也不能清理活跃比赛。目录256场/64 MiB与单场8 MiB预算在写盘前检查。

本批次新增23项单元回归，覆盖事件列表篡改（修复前先复现失败）、v1黄金样本、v2身份、目录容量、重复开赛、退出重组、管理员权限，以及组织/竞价/订单/补偿保存失败后的原字节保留和重试守恒。Java 21/25 各通过313项测试（44个测试类），失败/错误/跳过均为0；模块边界检查及两个JVM的发行/设计包签名工具自检通过。日志、固定哈希、故障矩阵和验证范围见 `docs/evidence/p6/lifecycle/summary.json`。新命令没有新增实服或真人证据，阶段门保持未通过。

P7 的 `ArenaRulesetStore` 将作者提交与公开 catalog 分开，提交绑定 content hash，作者不能自审，独立 OP 才能批准/拒绝，拒绝记录理由。审核状态跨重启恢复。Paper/Folia 自动流程覆盖待审不可见、批准后发布及拒绝版本隔离。

续开发同时修复 P7 草稿候选文档自授权路径：保存前检查持久文档的编辑许可，协作者只能修改尺寸/时长/单位预算，许可、版本、来源/依赖修改仅限原作者。4个新增回归覆盖自授权覆盖、撤销后恢复旧权限、协作者扩权/来源篡改以及代替其他作者创建草稿。

P7 后续稳定性批次固定新clone的原作者/ID/version/hash，核对草稿与发布路径、审核记录与文档身份，拒绝空审核文件。提交/公开ID绑定原作者，禁止其他作者抢占新版与覆盖内置starter；新作者创建前检查64目录预算。共享依赖闭包按唯一pin验证，并缓存最长剩余深度，避免DAG重复展开且保留16层限制；验证扩展至非Arena设计的递归依赖。

该批新增18项测试，8项在修复前复现失败。覆盖真实文件写盘失败、提交/批准/发布/拒绝重试、重启后审核人身份、旧v1黄金文件原字节与hash、发布归属、作者目录准入，以及共享32节点仅32次解析、深层复用仍超限、许可/pin/循环拒绝。Java 21/25 各通过389项（52个类），失败/错误/跳过均为0，边界与双JVM签名工具自检通过，见 `docs/evidence/p7/publication/summary.json`。批准与发布仍为两个原子文件，APPROVED可能暂时没有发布文件，需原审核员重试。没有新增实服、真人治理或反作弊验收证据，阶段门不变。

P8 的公开规则固定 ID/version/hash，match 只接收 roster UUID，每轮收齐全部参赛者动作后才原子推进。Pending actions 和 observers 会持久保存。Paper/Folia 实服客户端在 observer 确认 `pending=1` 后强制断开 Bob TCP socket，Bob 以相同 UUID 重连并恢复原 match/pending action，再完成回合和 replay。真人双队、公平性和性能仍未验收。

2026-11-25 从恢复分支 `b86995d` 继续修复 P8 提交边界：原流程会先封存前三位玩家的非法 claim，直到最后一位提交才拒绝整个回合，使已封存玩家无法重试。现在使用同一套引擎校验检查部分回合与完整回合，写盘前拒绝越界、超单位预算、非相邻领地、团队可用单位不足以及终局后的动作；无效提交保留原存档，合法重试可继续。新增9个提交/持久化场景和2个纯模拟校验用例，其中7个场景先复现失败，再验证修复。覆盖重启恢复已封存动作、只读 observer、身份冒用、重复提交、末位重试、团队预算和回放一致性。Java 21/25 各通过243项测试，零失败/错误/跳过；本机受内存限制使用 `-DforkCount=0` 与显式 JVM 上限完成 `clean verify`。本轮未重新运行 Paper/Folia 客户端实服流程，已有阶段门保持未通过。证据见 `docs/evidence/p8/arena-input-regression.json`。

续开发补齐 P8 比赛生命周期：新场次使用独立 run UUID 的 replay v2，同队伍/规则/seed 可以重复比赛；v1 固定样本继续恢复和推进且保持原 commitments。重启优先选择活跃参赛而不是最长历史，显式 `select`/`watch` 可以切换查看记录，`unwatch` 释放观战席位；退出赛前队伍不会被历史重新加入。当前查看选择和活跃参赛资格分开检查，查看历史不能绕过重复参赛或退队限制。`withdraw` 允许提交者撤回未结算输入，包括旧版本封存的无效 pending。目录128场/64 MiB与单场8 MiB预算在写入前执行，OP只能显式清理已结束且无 pending 的历史记录。

该生命周期批次新增21项测试，涵盖 v1 黄金样本、v2 场次/roster/hash篡改、重复比赛、断线和重启选择、观察者权限、退队重组、撤回恢复、目录预算及持久化失败后精确重试。Java 21/25 各通过278项测试（39个测试类），零失败/错误/跳过，发行与设计包签名自检均通过。首次针对性测试因宿主内存不足中断，缩小测试 JVM 后全部通过；Java 21 完整构建有 code-cache 用尽警告，因此不用于性能结论。日志、工件哈希和边界见 `docs/evidence/p8/lifecycle/summary.json`。新增命令尚未重新运行实服客户端验收，长局在8 MiB存档预算内的容量准入仍待继续核对，阶段门保持未通过。

后续容量批次复现：8人6000轮仅pass的合法回放也有16,148,334字节，超过8 MiB。现为新比赛加入保守JSON容量上界检查和目录增长预留：逐轮按目标解析/争夺、两队支出/再生及终局事件上界计算，另含全部pending和128名observer；超限在开赛前拒绝并提示可准入轮数，不改变公开规则的实际时长。长团队名/版本的固定测试样本中，默认1200轮4人预留4,094,757字节、8人预留6,745,173字节，分别可准入至2460/1492轮；数值由完整envelope动态计算，不是通用硬编码限制。64 MiB目录为活跃比赛预留最终上界，重启重新计算，比赛结束释放未来增长空间。

该容量批次新增6项回归，包含修复前开赛入口未拒绝超长局的失败证据、真实6000轮输出字节、争夺/结算事件上界、默认时长、目录预留恢复/释放和旧v1兼容。Java 21/25 各通过319项测试（45个类），失败/错误/跳过均为0，模块边界和双JVM签名工具自检通过。详见 `docs/evidence/p8/storage-capacity/summary.json`。旧版本已开始的超长局仍可能触及原8 MiB实际大小上限；未静默迁移或截断。无新增实服、真人或生产性能证据，阶段门不变。

## P9–P10

P9 coordinator 要求 adapter 声明 durable idempotency 和按 key lookup，包含付款、交付、补偿与不确定状态转人工复核。StarCore `main` 当前固定 SHA 为 `f769e62799e769a18fe51305b545869a17f80cd1`。现有 `TransactionFabric` 持久协调 transaction record，但公开 EconomyService 转账没有外部幂等键，且账本不原子执行钱包和背包副作用。因此真实 settlement adapter 和入口未注册，真实资产交易继续禁用。文件仓库的 create/replace 当前使用按 saga ID 的 JVM 与文件锁，并有跨仓库实例 revision 竞争回归用例；跨仓库 revision 竞争测试已在 Java 21 与 Java 25 通过。恢复时先查询外部幂等结果，再决定是否允许新的副作用，覆盖扣款或交付成功但持久化失败后授权过期、查询结果不确定等分支。

P9 并发稳定性批次先复现两条过期恢复竞态：另一请求可能在付款/交付仍未返回时提交失败/待退款状态。现通过repository排他范围将查询、授权、外部调用与保存串行化，Paper同时使用JVM分片锁和OS文件锁；同saga嵌套可重入，不同saga嵌套拒绝，异常释放锁。三条额外失败回归确认新建状态、原请求/permit和合法状态转移也需要保护，现拒绝跳过步骤或重开终局。重复begin返回原记录，过期仅阻止新建。

新增14项测试，Java 21/25各通过405项（54个类），失败/错误/跳过为0，模块边界和双JVM签名工具自检通过。三条真实文件写盘失败路径分别验证付款、交付、补偿在外部已应用后重启查询，避免重复扣款/退款或误退已交付交易。旧schema-v1已付款固定文件保持原字节，过期后继续补偿。两个独立本地JVM的探针分别验证正常释放和终止持锁进程后等待者继续，双JDK均通过；它不代表网络文件系统、多主机或真实provider验收。日志与哈希见 `docs/evidence/p9/concurrent-recovery/summary.json`，接入合同见 `SETTLEMENT_PROVIDER_CONTRACT.md`。外部副作用与本地文件仍非单次原子提交，UNKNOWN/INDETERMINATE规则保留，MANUAL_REVIEW没有生产处置入口；真实结算仍关闭。

P10 signed design envelope 使用 domain-separated Ed25519。OP 可从 `publisher-inbox` 加入 X.509 PEM key，列出指纹、添加新 key 和记录撤销原因；撤销状态跨重启保持且旧 key ID 不可复用。玩家导入仅写入个人目录并在读取时按当前 trust root 再验签。重复导入同样复验已保存内容，load/list 核对 schema v1 与设计 ID 派生路径，拒绝未知版本、错路径和损坏签名。自动流程使用临时测试 key，不构成运营信任根。当前没有第三方 extension bridge 和真实第三方运行证据。

## P11

2026-11-25 主 reactor `mvnw.cmd -B verify` 在 Java 21.0.13+9 与 Java 25.0.5+8 各通过232项测试，32份 Surefire report failures/errors/skipped 均为0。Java 21 工件 SHA 为 `8ce8920998217336c11ccf8ca28cddea02cf30df2604f8c35d09b0686d103e8b`，Java 25 工件 SHA 为 `8d32bafb9acbd5ce1943b5786fc1249a7a26cb9f0ba4da3803f1db1d1fb19fce`。本次日志与统计见 `docs/evidence/p11/maven-resume-java21.log`、`maven-resume-java25.log`、`resume-java21.json` 和 `resume-java25.json`。Paper/Folia 1.21.11 双端自动客户端覆盖 P1–P10，以及 P4/P6/P8 故障边界。

Folia 受控负载在4个分离 region 同时运行4个 P1 世界，每世界4个 agent，持续903秒，16次启动中包括12次自然重启。整个1123秒流程采224条五人在线样本，最低1m TPS 16.132，最高平均 tick 3.972 ms。这是受控 sandbox workload，不代表生产 world 或容量测试。原始样本为 `folia-runtime-sample.json`。

`4.0.0-rc.1` 从隔离源码副本构建，224项测试全过，Paper/Folia smoke、五客户端 P1–P10 和 signer ephemeral-key tamper test 通过。第二次 clean build 的 source/JAR/SBOM hashes 完全一致。候选 JAR SHA `8d6887351988c2f601026bb654b2ea9d3affe69b78df2216a8c8d535631e9913`，SBOM SHA `34fa65d853e71e2c9d328f70e4e90a771c15c80103fd2972c4b4be98793510c1`。RC 未使用正式签名私钥，非正式发布。P11 机器摘要和原始文件在 `docs/evidence/p11/`。

2026-11-25 的 `4.0.0-rc.2` 隔离 clean verify 通过232项测试，两次构建的源码/JAR/SBOM 哈希一致。候选 JAR SHA 为 `574db042891fb79a2a7e7dcd79e29cfcc0ffa7ebda688dfb67a8c3c93abafdbe`。Paper/Folia 五客户端完整流程均通过，包含双平台各八 seed 生态扫描和 Folia 跨区域移动。Ed25519 工具自检使用临时测试密钥并通过篡改检查，候选包尚待正式发行方签署。详细结果见 `docs/evidence/p11/release-candidate-rc2.json`。

## 未闭合阶段门

P1–P8 仍需真人玩家验收、留存和公平性研究。P5 candidate 已有双平台八 seed 实服覆盖，更广 seed/参数空间、长期研究与参数稳健性验收仍未完成。P9 需要指定真实 provider 的持久外部幂等副作用、授权和补偿故障审计。P10 需要运营方 trust-root 生命周期、真实第三方 bridge 及第三方验收。P11 需要生产规模/跨硬件容量研究和正式发行方签名。2026-11-25 已清理包管理器缓存并使用本机已安装的 JDK 21/25 恢复整仓构建，Paper/Folia 八 seed 客户端扫描已完成。
