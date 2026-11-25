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
| P5 | 实现已落地，阶段门未通过 | 已有v1实服和长期研究；新增v2成熟期/繁殖间隔/摄入储量上限及与world ID无关的物理随机选择。默认配置32/32、高突变31/32纯模拟长局保持三物种至1536步；旧v1按原哈希恢复。更广参数稳健性、v2实服长局和真人验收仍未完成 |
| P6 | 实现已落地，阶段门未通过 | Paper/Folia 既有客户端流程验证 compensation、退款、escrow 释放和 replay；新增独立场次、退出重组、活跃优先恢复、管理员终局清理及写盘失败回归。新增生命周期命令尚无实服验证，仍缺四名真人玩家故障比赛验收 |
| P7 | 代码和自动审核流程已落地，阶段门未通过 | 待审不可见、作者不能自审、独立 OP 批准/拒绝、拒绝理由和版本隔离已有实服覆盖。仍缺真人创作体验、运营治理和反作弊比赛验收 |
| P8 | 实现已落地，阶段门未通过 | Paper/Folia 验证四人回合、只读 observer、replay 以及 TCP 硬断线后 pending action/match ID/UUID 恢复。仍缺真人双队比赛、公平性和竞技性能验收 |
| P9 | 部分实现，阶段门未通过 | 已有整笔推进排他锁、持久saga、不可变request/permit、补偿和显式复核协议；复核只做三项查询，状态与审计原子保存。没有通过审计的真实经济/库存provider、生产审核授权adapter或命令入口，生产结算保持关闭 |
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

2026-11-26 继续围绕该候选执行64组单参数敏感性筛选。四个预先选定的困难场景中，仅降低decomposer intake到1、提高decomposer reproductionMass到8两组全部通过。随后两组各在原4个world与seed 52008–52023执行64次完整长局：低摄入组58/64通过，分world为16/16、16/16、16/16、10/16；繁殖阈值8组55/64通过，分world为16/16、16/16、16/16、7/16。128/128扩展均守恒并通过回放，失败样本完整保留；没有变更v1算法、默认程序或预算。

按birth/death事件重建代数后，旧候选world2/seed52008在1280步的4个剩余producer全部达到generation 8，最后一次出生为tick962，1283步全部死亡，出生预算尚未耗尽。已有单元回归现在固定这条代数边界。三世界全部成功仍不能推断另一世界的表现，64个单参数候选中没有一个通过完整的世界/种子组合。脚本与原始结果见`docs/evidence/p5/sensitivity/summary.json`；两个新profile继续仅供研究，没有新增实服或真人验收。

2026-11-26 新建生态舱升级至`genesis-ecology/2`。增加向上取整的半寿命成熟期、四分之一寿命成功繁殖间隔，以及以繁殖阈值限制新增摄入的储量规则；降低阈值不删除已有物质。所有死亡先释放空位再处理繁殖，新生个体下一步参与。处理次序、猎物和突变使用seed/tick/稳定出生序号，world ID继续绑定身份与证据。v1默认程序、排序、算法和哈希不变，恢复按已存engineVersion选择规则，文件schema仍为1。

两个未发布原型在同12组诊断样本中分别为1/12和8/12保持三物种至1536步；原型源码与原编译类逐字节核对，结果不可充作最终v2兼容样本。最终v2采用较长寿命默认程序，在32个固定/随机seed中32/32保持三物种至1536步；相同32个seed的最高突变率组为31/32。失败seed `-6448665863545292081`在1200步失去decomposer，已加入回归。两个负面对照各4组均灭绝；全部72组最终样本逐步守恒且回放通过。默认组在768步恢复后逐步一致，seed42最终哈希固定为`7c3dc98ebc3625d6d749b311d7e211c6125a2c4388f9a871fb2292c02d0620ff`。

该批新增13项引擎和2项服务测试，另扩展旧v1字节/哈希与版本拒绝回归，覆盖年龄边界、编辑/重启不清空繁殖间隔、降低储量阈值、空位竞争、世界改名物理轨迹等价及真实写盘失败重试。Java21/25各453项测试（59个类）全部通过，失败/错误/跳过为0；边界、双JVM跨进程锁和临时密钥签名自检通过。原始研究、全部失败样本、原型来源、构建日志及哈希见`docs/evidence/p5/lifecycle-v2/summary.json`，规则见`ECOLOGY_ENGINE_V2.md`。两个32组处理共享seed，不是64个独立seed；有限窗口、代数和出生预算仍不能证明无限或全参数共存。没有新增v2实服、真人或性能验收，P5阶段门保持未通过。

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

2026-11-26 P9增加显式复核协议：独立审核员提交固定review ID、saga ID、revision与case reference，由单独授权adapter提供证据。复核在同一排他范围内重新查询付款/交付/退款，不调用外部apply。全未发生则FAILED，已交付且未退款则COMPLETED，已退款且未交付则COMPENSATED，仅已付款则进入原COMPENSATION_REQUIRED；未知、矛盾或抹除既有APPLIED的结果保持MANUAL_REVIEW。原request/permit及幂等key不变。

状态与复核历史以同一外层schema-v2文件原子发布，16条/64 KiB预算，普通未复核文件继续写v1。新增23项单测覆盖27种查询组合、参与者自审/授权撤销、过期与重试、并发审核与退款排他、提交前/提交后故障、退款已生效后写盘失败、历史篡改、容量上限与从RC3导出的旧v1停止样本。Java21/25各438项测试（58个类）全部通过，失败/错误/跳过为0，模块边界、双JVM跨进程锁与临时密钥签名自检通过；3项Python构建器回归也通过。证据见`docs/evidence/p9/manual-reconciliation/summary.json`。这些代码尚未纳入旧RC3，当前为后续SNAPSHOT；没有生产审核授权adapter/命令、真实provider收据、实服或真人运营验收，真实结算仍关闭。

P10 signed design envelope 使用 domain-separated Ed25519。OP 可从 `publisher-inbox` 加入 X.509 PEM key，列出指纹、添加新 key 和记录撤销原因；撤销状态跨重启保持且旧 key ID 不可复用。玩家导入仅写入个人目录并在读取时按当前 trust root 再验签。重复导入同样复验已保存内容，load/list 核对 schema v1 与设计 ID 派生路径，拒绝未知版本、错路径和损坏签名。自动流程使用临时测试 key，不构成运营信任根。当前没有第三方 extension bridge 和真实第三方运行证据。

P10 后续稳定性批次复现并修复5项问题：缺少未来撤销容量、第4097条撤销会使库不可读、错误JSON标量被转换接受，以及跨store实例同ID/活跃容量的并发检查竞态。新读写持有目录级JVM/OS锁，新增key为未来撤销预留容量，满历史在写盘前拒绝；严格类型与4 KiB有界读取保持合法旧格式。新增10项回归覆盖这些边界、提交前/提交后故障、撤销先于公钥清理生效、重试保留原证据，以及旧v1固定公钥/撤销文件原字节兼容；另一store撤销后，已导入包的load/list/install均拒绝且不改写原包。

Java 21/25各通过415项测试（56个类），失败/错误/跳过为0，边界与双JVM签名工具自检通过，见 `docs/evidence/p10/trust-lifecycle/summary.json`。故障通过可注入的原子写入回调模拟，成功路径实际写入文件；并发验证为同JVM多实例和OS锁持有检查，未声称新的多进程/网络盘或实服验收。旧库满历史的容量迁移、运营方真实key、真实第三方bridge与验收仍未完成。

## P11

2026-11-25 主 reactor `mvnw.cmd -B verify` 在 Java 21.0.13+9 与 Java 25.0.5+8 各通过232项测试，32份 Surefire report failures/errors/skipped 均为0。Java 21 工件 SHA 为 `8ce8920998217336c11ccf8ca28cddea02cf30df2604f8c35d09b0686d103e8b`，Java 25 工件 SHA 为 `8d32bafb9acbd5ce1943b5786fc1249a7a26cb9f0ba4da3803f1db1d1fb19fce`。本次日志与统计见 `docs/evidence/p11/maven-resume-java21.log`、`maven-resume-java25.log`、`resume-java21.json` 和 `resume-java25.json`。Paper/Folia 1.21.11 双端自动客户端覆盖 P1–P10，以及 P4/P6/P8 故障边界。

Folia 受控负载在4个分离 region 同时运行4个 P1 世界，每世界4个 agent，持续903秒，16次启动中包括12次自然重启。整个1123秒流程采224条五人在线样本，最低1m TPS 16.132，最高平均 tick 3.972 ms。这是受控 sandbox workload，不代表生产 world 或容量测试。原始样本为 `folia-runtime-sample.json`。

`4.0.0-rc.1` 从隔离源码副本构建，224项测试全过，Paper/Folia smoke、五客户端 P1–P10 和 signer ephemeral-key tamper test 通过。第二次 clean build 的 source/JAR/SBOM hashes 完全一致。候选 JAR SHA `8d6887351988c2f601026bb654b2ea9d3affe69b78df2216a8c8d535631e9913`，SBOM SHA `34fa65d853e71e2c9d328f70e4e90a771c15c80103fd2972c4b4be98793510c1`。RC 未使用正式签名私钥，非正式发布。P11 机器摘要和原始文件在 `docs/evidence/p11/`。

2026-11-25 的 `4.0.0-rc.2` 隔离 clean verify 通过232项测试，两次构建的源码/JAR/SBOM 哈希一致。候选 JAR SHA 为 `574db042891fb79a2a7e7dcd79e29cfcc0ffa7ebda688dfb67a8c3c93abafdbe`。Paper/Folia 五客户端完整流程均通过，包含双平台各八 seed 生态扫描和 Folia 跨区域移动。Ed25519 工具自检使用临时测试密钥并通过篡改检查，候选包尚待正式发行方签署。详细结果见 `docs/evidence/p11/release-candidate-rc2.json`。

2026-11-26 RC2稳定性续开发已汇入未签署 `4.0.0-rc.3` 候选。候选构建器支持显式 `--fork-count 0`，记录实际JDK/参数/日志hash，证据按版本命名且拒绝覆盖已有文件。新增3项Python回归验证失败/跳过/空报告不能包装、隔离副本不带旧工件或修改源码版本、旧证据不被覆盖。CI加入跨进程结算锁探针并归档SBOM，在Linux Temurin Java21/25各通过415项Java测试、3项Python回归、锁探针和双签名自检；对应提交 `1eb14b8`，workflow run `45658631648`。

RC3使用两个独立源码副本和Microsoft JDK21分别clean verify，各415项测试通过；源码树/JAR/SBOM哈希完全一致。候选JAR SHA256为 `fbc0ce3213fb271d5c9b09c826fcda8ea107bac0d703d75198b2d510d7184001`，SBOM为 `e0c2a36d3f424029b7df1e0ecec1dbca404fb0aa8f8d550811aab9c12a4b8719`，含61组件。Windows Java21/25均对同一RC3执行跨进程锁和临时密钥签名自检。证据见 `docs/evidence/p11/rc3-recovery/summary.json`。重复性只覆盖同主机同JDK两次构建；不同JDK/主机的插件hash不要求相同。尚未对RC3重跑Paper/Folia实服工作流，无正式私钥签署、GitHub release/tag或生产部署；全阶段门不因此自动通过。

后续SNAPSHOT继续增加P9显式复核及生态v2生命周期。当前Windows Java21/25各453项测试（59个类）通过，两个JVM跨进程锁与临时密钥签名自检通过；最新证据见`docs/evidence/p5/lifecycle-v2/summary.json`。这些改动尚未纳入上述RC3候选，没有新增实服或正式发行签署证据。

2026-11-26 后台worker关闭加固将P1/P5/P6/P8目录所有权绑定到实际执行器终止。原关闭序列的独立复现中，任务忽略中断时close返回后仍继续完成；这不是实服故障记录。新实现先停止准入、等待5秒排空，再取消队列/中断并等待1秒；未结束的任务保持目录锁，新实例不能加载并覆盖同一目录。构造失败释放锁；同JVM/独立类加载器在打开重复文件句柄前拒绝，保留POSIX锁。插件汇总各服务结果，实验室/radio最终保存失败或worker未终止不再报告干净退出，单个运行时异常不阻止其他服务清理。

本批新增12项单元回归：9项worker、2项真实生态存档和1项实验室最终检查点。Java21/25各465项测试（62个类）通过，失败/错误/跳过为0；三项Python回归、模块边界、双JVM的目录锁/结算锁与临时密钥签名自检通过。独立进程目录锁探针分别验证正常释放、关闭超时后仍拒绝新进程，以及终止持锁进程后获取；包含同进程/不同类加载器拒绝后OS锁仍有效。证据见`docs/evidence/p11/worker-lifecycle/summary.json`。未执行命令不持久恢复，正在运行的操作可能晚到提交；这些测试不是Paper/Folia热重载、实体回调、网络文件系统或真人验收，全部原阶段门保持未通过。

## 未闭合阶段门

P1–P8 仍需真人玩家验收、留存和公平性研究。P5 candidate 已有双平台八 seed 实服覆盖，更广 seed/参数空间、长期研究与参数稳健性验收仍未完成。P9 需要指定真实 provider 的持久外部幂等副作用、授权和补偿故障审计。P10 需要运营方 trust-root 生命周期、真实第三方 bridge 及第三方验收。P11 需要生产规模/跨硬件容量研究和正式发行方签名。2026-11-25 已清理包管理器缓存并使用本机已安装的 JDK 21/25 恢复整仓构建，Paper/Folia 八 seed 客户端扫描已完成。
