# P0 公共接口冻结候选 v1

2026-11-19 的 P0 整仓 clean verify 已通过，公共API与设计fixture作为后续并行开发基线。此冻结仅约束接口，不提前声明P0服务器或后续阶段验收完成。

- `dev.stargenesis.api.design`：DesignId、DesignKind、DesignDocument、DesignMetadata、DesignDependency、DesignPermissions。
- `dev.stargenesis.api.sim`：AgentId、SimulationClock、SimulationWorld、SimulationBudget、StepInput、StepResult、EvidenceId、EventRecord、ResourceLedger、EventOutbox。
- ID、排序、幂等和owner线程语义以 PRODUCT_CONTRACT.md 和合同测试为准。
- `dev.stargenesis.design.DesignCodec`：load、seal、write、migrateV0；schemaVersion=1的五种文档和内置例子持续作为兼容fixture。
- P1在sim模块新增实现和不可变视图/快照/回放值，不改上述签名。Paper通过受控执行器驱动世界，区域/实体调度只消费不可变视图。
- P2/P3/P4可提案扩展模型，不能绕过codec/预算/权限；语义变化提高schema或engine版本并保留旧fixture与拒绝测试。

变更流程：列出受影响消费者→提出最小接口差异→增加合同/迁移测试→串行整仓verify→最小回放→适当实机验收。不得并发写他人模块或同一target。

P9复核增量接口：integrations增加SettlementReviewRequest/Authorizer/Review/Coordinator；SettlementSagaRepository新增默认拒绝的reviews/commitReview能力，旧实现不会自动获得终局重开权限。消费者为FileSettlementSagaRepository与后续经审计的运营适配层，不改`dev.stargenesis.api`签名。自动coordinator和复核共用SettlementOperation.forPhase，固定旧幂等key；普通状态转移保持原契约。外层持久v2只用于已有复核证据的文件，v1保持读写兼容。协议、预算和生产接入边界见`SETTLEMENT_PROVIDER_CONTRACT.md`。
