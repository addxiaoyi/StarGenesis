# StarGenesis 产品与工程契约 v1

产品口号：**玩家发明规则，世界检验发明。** 本契约冻结 P0 的共同边界；不是 P1–P11 已完成声明。

## 玩法目标与当前范围

以结构、有限行为图、协议、契约、组织和实验规则的组合产生玩法。5 分钟修改公共设计并观察反馈，15–20 分钟完成竞技，长期复用发明与跨玩家协作。不得用宠物升级、抽卡、固定任务或数值膨胀替代开放组合。

P0 实现可启动诊断插件、可校验的五种设计格式、模拟接口、守恒账本和能力发现基线。P1–P8 已有确定性沙盒、创作器、radio/contract、生态、组织工厂、审核后发布的 ArenaRuleset UGC 与多玩家回合竞技代码；Paper/Folia 自动客户端覆盖已实现的玩家命令，真人验收仍逐阶段待完成。P5 自动 parameter profile 扫描验证了 matter 守恒与 replay，四组对照规则在 SNAPSHOT/RC 的 Paper/Folia 实服运行中均在500 tick预算前灭绝；一组固定 seed 52008 candidate 在四次 Paper/Folia SNAPSHOT/RC 运行中都到达500 tick并保留三物种。纯 Java 模拟还对两个归档的 Alice worldId 分别扫描100个 seeds，200/200 到500 ticks时保留三物种，但这不是服务器负载证据。2026-11-25 完成 Paper/Folia 各八 seed 自动客户端实服扩展，16/16 均到500 tick保留三物种，matter 守恒与 replay 校验全部通过。P5 的广泛 seed 稳健性、长期生态平衡与真人验证仍未完成。P9 已有 saga 协调协议和持久文件 repository，但真实结算 adapter 未通过幂等/权限/故障验收，交易保持关闭。P10 有 Ed25519 signed design import、publisher 包签署 CLI 和 OP 管理的 trust-root 添加/轮换/撤销流程，但尚无运营方真实 key、第三方 bridge acceptance。P11 有 CycloneDX aggregate SBOM、15分钟 Folia 受控沙盒负载和 `4.0.0-rc.1` 隔离候选构建；生产规模性能、玩家留存和发行方正式签名验收仍未完成。

## 模块与冻结接口

| 模块 | P0 实际责任 | 后续消费者 |
|---|---|---|
| stargenesis-api | 不可变 ID/元数据、SimulationClock/World、输入/结果/事件/预算/ResourceLedger/EventOutbox | 所有模块 |
| stargenesis-design | Blueprint/Program/Protocol/Contract/ArenaRuleset、严格 JSON 编解码、SHA-256、显式迁移、Ed25519 signed envelope、Schema/示例、蓝图物理评估、typed-program IR/解释器与有界编辑撤销 | P2/P3/P4/P7/P10 |
| stargenesis-sim | 单线程时钟、守恒账本、内存 outbox、checkpoint/replay 与封闭 matter 生态 | P1/P5 |
| stargenesis-integrations | 只读插件清单、版本审计描述符、能力逐项验证、失效隔离和持久 settlement saga contract | P9/P10 |
| stargenesis-paper | 生命周期、命令、权限、配置硬限制、设计加载自检、作者私有工作区/replay 持久化、程序/生态/竞技适配、签名设计导入 | 玩家入口/区域调度 |

仅创建有代码和测试的模块。Program 文档通过格式验证不代表其算法已经编译/执行；当前显式编译只接受已实现的旧格式子集。typed IR 使用独立 schema v1 并可由 Paper 玩家工作区保存，解释器不直接修改世界；Paper 模拟适配器将受限动作映射到资源沙盒的有界输入。Protocol/Contract/ArenaRuleset 文档存在不代表网络、交易、竞技可运行。

公共 API 位于 `dev.stargenesis.api`。设计实现位于 `dev.stargenesis.design`；第三方类型不得进入上述 API 或纯模拟。变更使用提案→增加兼容/迁移测试→合并队列→整仓 verify 的顺序，不由并行工作者随意改签名。

## 身份、版本与可信边界

- DesignId 使用有界 `namespace:path`，拒绝 `..`，不可解释为磁盘路径；AgentId 使用 UUID，其字典顺序为 UUID ASCII 字符串序。
- 文档包含 stable ID、schemaVersion、语义版本、UUID 作者、来源、精确版本/哈希依赖、权限与哈希。集合防御复制并不可变。
- 当前持久格式为 schemaVersion=1。只有显式 v0→v1 迁移；未知版本拒绝，不自动覆盖原文。迁移保留前后哈希，参见 MIGRATIONS.md。
- SHA-256 用于内容完整性与依赖固定，不是作者数字签名或授权证明。P10 signed envelope 可证明受信 Ed25519 publisher key 签过内容，但不替代 publisher trust-root 生命周期、设计作者许可或游戏操作授权。
- 玩家只能组合服务端审核的有限原语；不接收 Java、命令、反射、文件/网络 IO、任意脚本或无界递归。P0 不执行玩家程序。

## 确定性与线程模型

- SimulationWorld 的权威状态由单个受控工作线程拥有；输入仅通过不可变 StepInput，输出仅通过不可变 StepResult。当前引擎契约为 `genesis-sim/1`。
- tick 是离散逻辑时间，禁止读取墙钟参与结果；种子显式输入。StepInput 按 AgentId、sequence 排序，重复序号拒绝。所有资源以 long 整数计量，溢出必须在写入前失败。
- 后续 P1 必须固定 RNG 算法、地形/动作顺序、状态哈希编码、检查点和回放版本。P0 接口不等于这些引擎算法已经完成。
- Bukkit、helsteraMobs、CraftEngine、实体和背包调用留在适配层，按目标服务器/区域/实体调度。不得把“主线程”解释为 Folia 的全世界同步线程。
- 显示实体仅为模拟投影；不根据其位置、动画或物品外观推导权威模拟状态。

## 资源、幂等与恢复

- 模拟材料、能源、耐久与真实库存/货币是不同资产域。实验得分不能兑付真实物品；P0 没有真实结算路径，配置企图启用时拒绝启动。
- EvidenceId 格式 `sg:v1:<world>:<tick>:<sequence>`；world 不含冒号。用于事件和模拟转移去重，不能直接当作第三方已经支持的幂等参数。
- ResourceLedger 只在初始场景中注入有限资产；随后只能账户间转移，禁止 mint。相同 evidence + 同请求不重复支付；同 evidence + 不同请求拒绝。
- 余额不足也消耗该 evidence；补充余额后须以新 evidence 重试。账本 checkpoint 包含余额与成功/失败历史，恢复后继续去重。容量达到上限时停止，不能删除历史后重用旧键。
- MemoryEventOutbox 是内存参考实现，不具备崩溃持久性。P9 已有持久 saga protocol 和本地 repository；真实世界订单仍须额外通过真实 adapter 的外部幂等、权限、compensation 和第三方故障恢复验收后才启用。

## 权限、预算与公平

实验与竞技不得更改生存建筑、实际库存或他人造物。P2 蓝图默认私有，作者可显式开放只读查看、指定协作者和许可克隆。协作者仅修改结构，保存时以持久文档的当前授权和基准哈希为准；许可、版本、来源/依赖由原作者控制。克隆使用新 ID，副本私有并记录来源作者、版本和精确哈希；publicRead 不能替代 allowClone。P7 草稿同样以持久授权为准，候选文档不可自授权限。蓝图质量、代价和能力是抽象模拟单位，不对应真实材料、物品或实体。作者/编辑者权限、克隆授权与来源须在 P2/P7/P10 各入口检查；仅存在元数据并不构成授权证据。

默认预算：每造物每步128节点、16造物、每步8路径请求、最多3000步、感知半径16、每步64消息；硬上限见 SimulationBudget。P0 对配置做校验，后续执行器负责实际计量并给出可理解的超限事件。

P5 默认实验500 tick，可在新建时显式选择1–1536 tick，覆盖最多三个512步寿命周期的观察窗口。预算不保证存活；时长、出生和代数仍有限。既有实验不自动延长，旧回放配置与 commitments 不改写。长期纯模拟研究记录各物种首次灭绝和生命周期计数，不能把“500步仍存活”或单物种延续当作三物种长期共存。

P6 组织关系与比赛历史分别持久化。新工厂场次使用独立 UUID，全部四名成员在线且空闲才能开赛；活跃参赛时不能退队或重复参赛。组队、竞价、订单和补偿均先写盘再报告成功，失败保留原状态以便重试。普通成员不能 reset 共享比赛；`stargenesis.factory.manage` 管理员仅能显式清理已结束且订单全部完成或补偿的历史。目录256场/64 MiB、单文件8 MiB，超限不覆盖旧数据。工厂模拟账户不连接真实资产。

公共入门模板免费；核心竞技统一预算。深度提供策略选择，不提供无上限数值优势。离线产出默认关闭；真实收益与榜单须以可复现结果和幂等证据结算。

P8 每次新比赛使用独立场次 UUID，精确固定规则和 roster，旧回放保留 v1 commitments。查看历史或观战不改变参赛占用；存在活跃参赛时不能重复开赛或退出队伍。每个未结算动作只有其提交者能撤回，已结算回合不可改写。比赛文件写入前检查128场/64 MiB目录预算和8 MiB单场预算；超限拒绝写入并保留旧数据。已完成历史仅能由具备 `stargenesis.arena.manage` 权限的管理员显式清理，活跃比赛不得清理。新命令单元测试不等价于实服或真人竞技验收。

## 阶段门与并行顺序

P0 基线冻结后：A 负责 P1→P5 模拟，B 负责 P2→P3 创造，C 持续审计适配并在 P9/P10 激活，D 从 P1 补玩法体验。P4 依赖 Program IR 与事件日志，P6 依赖 P4，P7 使用已实现原语，P8 整合成熟系统，P9 依赖真实经济事务，P11 汇总真实集成与玩家验证。

每次合并运行结构/Schema 合同测试、纯 Java 单元测试、正式 `./mvnw -B verify` 和已存在的最小回放。服务器、真实客户端、多人和长期留存是不同证据等级，禁止互相冒充。阶段未通过所列门槛保持未验收。
