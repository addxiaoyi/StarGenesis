# P0 公共接口冻结候选 v1

2026-11-19 的 P0 整仓 clean verify 已通过，公共API与设计fixture作为后续并行开发基线。此冻结仅约束接口，不提前声明P0服务器或后续阶段验收完成。

- `dev.stargenesis.api.design`：DesignId、DesignKind、DesignDocument、DesignMetadata、DesignDependency、DesignPermissions。
- `dev.stargenesis.api.sim`：AgentId、SimulationClock、SimulationWorld、SimulationBudget、StepInput、StepResult、EvidenceId、EventRecord、ResourceLedger、EventOutbox。
- ID、排序、幂等和owner线程语义以 PRODUCT_CONTRACT.md 和合同测试为准。
- `dev.stargenesis.design.DesignCodec`：load、seal、write、migrateV0；schemaVersion=1的五种文档和内置例子持续作为兼容fixture。
- P1在sim模块新增实现和不可变视图/快照/回放值，不改上述签名。Paper通过受控执行器驱动世界，区域/实体调度只消费不可变视图。
- P2/P3/P4可提案扩展模型，不能绕过codec/预算/权限；语义变化提高schema或engine版本并保留旧fixture与拒绝测试。

变更流程：列出受影响消费者→提出最小接口差异→增加合同/迁移测试→串行整仓verify→最小回放→适当实机验收。不得并发写他人模块或同一target。
