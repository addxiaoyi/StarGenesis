# 阶段状态

更新时间：2026-11-25。状态以当前代码和可复现证据为准。未执行或证据不足的阶段门保持未通过。

## 差异审计

远端 main 初始提交 `8b7489346721ad4127f87f95d8e89775d218c74a` 只有 README 和 MIT LICENSE。本地建立五个有实际代码的 Maven 模块。StarCore、helsteraMobs 与 SenLuo/CraftEngine 按固定源码 SHA 审计，详见 `INTEGRATION_AUDIT.md` 和 `docs/audits/`。第三方源码、插件安装或模拟 adapter 均不等于实服兼容。

## 当前阶段

| 阶段 | 状态 | 已有实现与未完成验收 |
|---|---|---|
| P0 | 已通过基础验收 | 五模块、设计格式/API/权限基线已落地；88项测试、全新 Maven 缓存 verify 和 Paper/Folia 1.21.11-132/14 控制台启停通过，见 `docs/evidence/p0/`。这不包含第三方功能或真人玩法验收 |
| P1 | 实现已落地，阶段门未通过 | Paper/Folia 客户端验证实验启动、停止、回放和检查点恢复。仍缺真人交互验收 |
| P2 | 实现已落地，阶段门未通过 | Paper/Folia 客户端创建、编辑、预览、保存并重开个人蓝图。仍缺真人编辑体验和长期重启/隔离验收 |
| P3 | 实现已落地，阶段门未通过 | typed program 编辑、沙盒执行和 replay 已覆盖。仍缺真人程序工作流和多人性能验收 |
| P4 | 实现已落地，阶段门未通过 | Paper/Folia 实服流程验证 at-least-once poll、ack、收件人断线重投和跨 tick 去重。仍缺真人通信体验与长期网络负载验收 |
| P5 | 实现已落地，阶段门未通过 | 四组实服对照 profile 均会灭绝。固定候选 profile 在 Paper/Folia SNAPSHOT 与 RC 共四次实服运行中到达500 tick并保留三物种。两个归档实服 Alice worldId 上的200次纯模拟 seed 扫描均保留三物种。另以八个 worldId、十个 seed、五组参数进行400次纯模拟筛选，均到达500 tick；最佳组最少物种人口均值5.2125、最小值4。纯模拟结果不代表实服鲁棒性或平衡验收。Paper/Folia 已各完成八 seed 客户端实服扫描，16/16 到500 tick保留三物种并通过守恒/replay；仍缺更广实服 seed 覆盖、长期和真人生态验收 |
| P6 | 实现已落地，阶段门未通过 | Paper/Folia 客户端流程触发部分生产故障，验证 compensation、credits/ore 退款、parts escrow 释放和 replay。仍缺四名真人玩家故障比赛验收 |
| P7 | 代码和自动审核流程已落地，阶段门未通过 | 待审不可见、作者不能自审、独立 OP 批准/拒绝、拒绝理由和版本隔离已有实服覆盖。仍缺真人创作体验、运营治理和反作弊比赛验收 |
| P8 | 实现已落地，阶段门未通过 | Paper/Folia 验证四人回合、只读 observer、replay 以及 TCP 硬断线后 pending action/match ID/UUID 恢复。仍缺真人双队比赛、公平性和竞技性能验收 |
| P9 | 部分实现，阶段门未通过 | 有持久 saga、revision、permit、补偿和 manual review。没有通过外部幂等/权限/故障审计的真实经济/库存 adapter，生产结算保持关闭 |
| P10 | 签署/导入和 trust-root 生命周期已落地，阶段门未通过 | 有 Ed25519 包签署/验证、OP 公钥添加/轮换/撤销和私有导入。仍缺运营方真实 trust root、真实第三方 bridge 和第三方验收 |
| P11 | 部分实现，阶段门未通过 | 有 CycloneDX 1.6 SBOM、scale probe、双平台自动流程、15分钟受控 Folia 沙盒负载、可重复 RC 构建。仍缺生产规模容量研究、真人验收/留存研究、最终正式发行签名 |

## P1–P3

P1 在纯 Java 模块实现确定性地形、寻路、采集、投递、能源预算、checkpoint 和 replay。Paper 提供虚拟库存投影，Folia 使用命令式工作流且不创建玩家背包界面。每次合并运行测试和边界扫描。自动客户端不是真人验收。

P2 使用服务器模块白名单和有界物理代价进行蓝图评估。个人 workspace 支持编辑、预览、保存和撤销，文件按 UUID 与设计 ID 哈希隔离并用基准 hash 防止覆盖冲突。Paper/Folia 五客户端已创建、修改和重开蓝图。

P3 有35个封闭 typed primitive、IR schema/hash、解释器、断点/步进/撤销和有限资源沙盒。沙盒执行不访问 Minecraft world、真实库存或经济服务。自动客户端已编辑程序并验证 replay。

## P4–P5

P4 协议以固定 ID/hash 验证消息字段、recipient、tick/sequence 和 payload budget。消息至少一次投递，ack 前重复 poll 返回相同 envelope。Paper/Folia 实服客户端已验证重复 poll、ack 清空、recipient socket 断线重连后原消息重投，以及跨 tick 相同幂等键不重复投递。结果在 `docs/evidence/p11/paper-reliability-retest-workflow-result.json` 与 `folia-reliability-retest-workflow-result.json`。

P5 以 `ConservingResourceLedger` 在 soil、detritus 和 organism biomass 间转移 matter。每步记录 state/event commitment 并支持 replay。自动实服四组 seed 52001–52004 对照 profile 在 Paper/Folia SNAPSHOT/RC 共八次运行中均灭绝。候选参数固定为 producer `(6,2,512,0)`、grazer `(1,12,512,0)`、decomposer `(1,12,512,0)`。seed 52008 在 SNAPSHOT 与 RC 的 Paper/Folia 共四次实服运行均达到500 tick，最终 population 为26/2/4、matter=1000，replay通过。

另基于两份从 Paper/Folia 实服回放读取的 Alice worldId，纯 Java 模拟扫描 seed 52008–52107。两个 worldId 各100/100次运行均保持三物种至500 tick，population 达到32，未发生 extinction。它是 simulation-only 结果，不是服务器实测，也不证明广泛参数空间或生态平衡。2026-11-25 在 Paper 1.21.11-132 与 Folia 1.21.11-14 各执行 seed 52008–52015 的客户端顺序扫描，16/16 运行达到500 tick，population 均为26/2/4、matter=1000，回放全部通过。Folia 五客户端分布于不同区域。结果见 `paper-seed-sweep-workflow-result.json` 和 `folia-seed-sweep-workflow-result.json`。这些固定规则的寿命上限为512，500 tick结果仍不足以证明长期生态平衡。原始实服样本见 `paper-ecology-study-retest-workflow-result.json` 和 `folia-ecology-study-retest-workflow-result.json`。

## P6–P8

P6 由四名不同 UUID 玩家组成两个组织、竞价并下单。额外实服流程使用 roster 派生的确定 fault seed 触发部分生产失败，断言 `FAILED_RECOVERED`、recovered order、credits/ore 退回至预期余额、parts escrow 释放和 replay。结果见 `paper-factory-compensation-retest-workflow-result.json` 与 Folia 对应文件。这仍是五自动客户端，不替代真人比赛。

P7 的 `ArenaRulesetStore` 将作者提交与公开 catalog 分开，提交绑定 content hash，作者不能自审，独立 OP 才能批准/拒绝，拒绝记录理由。审核状态跨重启恢复。Paper/Folia 自动流程覆盖待审不可见、批准后发布及拒绝版本隔离。

P8 的公开规则固定 ID/version/hash，match 只接收 roster UUID，每轮收齐全部参赛者动作后才原子推进。Pending actions 和 observers 会持久保存。Paper/Folia 实服客户端在 observer 确认 `pending=1` 后强制断开 Bob TCP socket，Bob 以相同 UUID 重连并恢复原 match/pending action，再完成回合和 replay。真人双队、公平性和性能仍未验收。

## P9–P10

P9 coordinator 要求 adapter 声明 durable idempotency 和按 key lookup，包含付款、交付、补偿与不确定状态转人工复核。StarCore `main` 当前固定 SHA 为 `f769e62799e769a18fe51305b545869a17f80cd1`。现有 `TransactionFabric` 持久协调 transaction record，但公开 EconomyService 转账没有外部幂等键，且账本不原子执行钱包和背包副作用。因此真实 settlement adapter 和入口未注册，真实资产交易继续禁用。文件仓库的 create/replace 当前使用按 saga ID 的 JVM 与文件锁，并有跨仓库实例 revision 竞争回归用例；跨仓库 revision 竞争测试已在 Java 21 与 Java 25 通过。恢复时先查询外部幂等结果，再决定是否允许新的副作用，覆盖扣款或交付成功但持久化失败后授权过期、查询结果不确定等分支。

P10 signed design envelope 使用 domain-separated Ed25519。OP 可从 `publisher-inbox` 加入 X.509 PEM key，列出指纹、添加新 key 和记录撤销原因；撤销状态跨重启保持且旧 key ID 不可复用。玩家导入仅写入个人目录并在读取时按当前 trust root 再验签。重复导入同样复验已保存内容，load/list 核对 schema v1 与设计 ID 派生路径，拒绝未知版本、错路径和损坏签名。自动流程使用临时测试 key，不构成运营信任根。当前没有第三方 extension bridge 和真实第三方运行证据。

## P11

2026-11-25 主 reactor `mvnw.cmd -B verify` 在 Java 21.0.13+9 与 Java 25.0.5+8 各通过232项测试，32份 Surefire report failures/errors/skipped 均为0。Java 21 工件 SHA 为 `8ce8920998217336c11ccf8ca28cddea02cf30df2604f8c35d09b0686d103e8b`，Java 25 工件 SHA 为 `8d32bafb9acbd5ce1943b5786fc1249a7a26cb9f0ba4da3803f1db1d1fb19fce`。本次日志与统计见 `docs/evidence/p11/maven-resume-java21.log`、`maven-resume-java25.log`、`resume-java21.json` 和 `resume-java25.json`。Paper/Folia 1.21.11 双端自动客户端覆盖 P1–P10，以及 P4/P6/P8 故障边界。

Folia 受控负载在4个分离 region 同时运行4个 P1 世界，每世界4个 agent，持续903秒，16次启动中包括12次自然重启。整个1123秒流程采224条五人在线样本，最低1m TPS 16.132，最高平均 tick 3.972 ms。这是受控 sandbox workload，不代表生产 world 或容量测试。原始样本为 `folia-runtime-sample.json`。

`4.0.0-rc.1` 从隔离源码副本构建，224项测试全过，Paper/Folia smoke、五客户端 P1–P10 和 signer ephemeral-key tamper test 通过。第二次 clean build 的 source/JAR/SBOM hashes 完全一致。候选 JAR SHA `8d6887351988c2f601026bb654b2ea9d3affe69b78df2216a8c8d535631e9913`，SBOM SHA `34fa65d853e71e2c9d328f70e4e90a771c15c80103fd2972c4b4be98793510c1`。RC 未使用正式签名私钥，非正式发布。P11 机器摘要和原始文件在 `docs/evidence/p11/`。

2026-11-25 的 `4.0.0-rc.2` 隔离 clean verify 通过232项测试，两次构建的源码/JAR/SBOM 哈希一致。候选 JAR SHA 为 `574db042891fb79a2a7e7dcd79e29cfcc0ffa7ebda688dfb67a8c3c93abafdbe`。Paper/Folia 五客户端完整流程均通过，包含双平台各八 seed 生态扫描和 Folia 跨区域移动。Ed25519 工具自检使用临时测试密钥并通过篡改检查，候选包尚待正式发行方签署。详细结果见 `docs/evidence/p11/release-candidate-rc2.json`。

## 未闭合阶段门

P1–P8 仍需真人玩家验收、留存和公平性研究。P5 candidate 已有双平台八 seed 实服覆盖，更广 seed/参数空间、长期研究与参数稳健性验收仍未完成。P9 需要指定真实 provider 的持久外部幂等副作用、授权和补偿故障审计。P10 需要运营方 trust-root 生命周期、真实第三方 bridge 及第三方验收。P11 需要生产规模/跨硬件容量研究和正式发行方签名。2026-11-25 已清理包管理器缓存并使用本机已安装的 JDK 21/25 恢复整仓构建，Paper/Folia 八 seed 客户端扫描已完成。
