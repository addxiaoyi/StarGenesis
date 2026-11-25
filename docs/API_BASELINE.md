# P0 公共接口冻结候选 v1

2026-11-19 的 P0 整仓 clean verify 已通过，公共API与设计fixture作为后续并行开发基线。此冻结仅约束接口，不提前声明P0服务器或后续阶段验收完成。

- `dev.stargenesis.api.design`：DesignId、DesignKind、DesignDocument、DesignMetadata、DesignDependency、DesignPermissions。
- `dev.stargenesis.api.sim`：AgentId、SimulationClock、SimulationWorld、SimulationBudget、StepInput、StepResult、EvidenceId、EventRecord、ResourceLedger、EventOutbox。
- ID、排序、幂等和owner线程语义以 PRODUCT_CONTRACT.md 和合同测试为准。
- `dev.stargenesis.design.DesignCodec`：load、seal、write、migrateV0；schemaVersion=1的五种文档和内置例子持续作为兼容fixture。
- P1在sim模块新增实现和不可变视图/快照/回放值，不改上述签名。Paper通过受控执行器驱动世界，区域/实体调度只消费不可变视图。
- P2/P3/P4可提案扩展模型，不能绕过codec/预算/权限；语义变化提高schema或engine版本并保留旧fixture与拒绝测试。

变更流程：列出受影响消费者→提出最小接口差异→增加合同/迁移测试→串行整仓verify→最小回放→适当实机验收。不得并发写他人模块或同一target。

P1/P5/P6/P8关闭增量仅在paper模块：`PersistentWorker`封装串行命令/定时执行器、目录所有权与可检查的ShutdownResult，四个控制器提供isStopped供插件汇总关闭结果。RadioController同时暴露最终保存是否成功。消费者为插件生命周期与本地探针，不改公共api、模拟规则或持久schema。跨类加载器/JVM所有权和关闭预算见`WORKER_LIFECYCLE.md`。

P9复核增量接口：integrations增加SettlementReviewRequest/Authorizer/Review/Coordinator；SettlementSagaRepository新增默认拒绝的reviews/commitReview能力，旧实现不会自动获得终局重开权限。消费者为FileSettlementSagaRepository与后续经审计的运营适配层，不改`dev.stargenesis.api`签名。自动coordinator和复核共用SettlementOperation.forPhase，固定旧幂等key；普通状态转移保持原契约。外层持久v2只用于已有复核证据的文件，v1保持读写兼容。协议、预算和生产接入边界见`SETTLEMENT_PROVIDER_CONTRACT.md`。

P5生命周期语义升级至`genesis-ecology/2`：EcologyWorld三参数构造用于新版本，新增显式engineVersion构造、engineVersion()/supportsEngine()与LEGACY_ENGINE_VERSION常量；restore/verifier按已存版本选择规则。公共api模块、EcologyReplay/EcologyConfig字段和文件schema不变。消费者为生态service、verifier与研究脚本；旧研究显式选v1。v1固定样本和长期灭绝反例保持原哈希/事件，新版本追加成熟期、繁殖间隔、摄入储量上限和与world ID无关的物理随机输入。算法、排序、预算与迁移合同见`ECOLOGY_ENGINE_V2.md`。
