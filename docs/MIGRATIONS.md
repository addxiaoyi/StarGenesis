# 版本与迁移

## P0 基线

工程版本 `4.0.0-SNAPSHOT`；公共设计 schemaVersion=1；配置 config-version=1；模拟接口 `genesis-sim/1`。这是研发构建，不是 P0–P11 正式发布完成声明。

持久设计格式、配置版本、引擎版本和插件发布版本分别升级。加载未知设计或配置版本时明确拒绝，不猜测兼容性。

## 设计 v0 → v1

`DesignCodec.migrateV0(String)` 验证旧文档哈希，显式把 `metadata.revision` 改为 `metadata.version`、schemaVersion 改为1，重新计算内容哈希，并返回前后哈希、迁移后的 JSON 和不可变对象。普通 `load` 不执行迁移。

v0 是本项目带测试的遗留契约样例，不声称曾在生产服发布。迁移前保留原文与哈希，不覆盖唯一副本；若依赖仍固定旧哈希，必须显式更新依赖并重新验证。Schema/示例/拒绝用例在 stargenesis-design 模块内。

## 配置与账本

P0 不提供在线 reload 命令。调整 config.yml 后通过受控重启应用；越界预算、未知 config-version 或启用真实结算会拒绝插件启动。先备份插件数据目录，再升级。

当前时钟/账本 checkpoint 是进程可序列化的不可变值对象；MemoryEventOutbox 不持久化。P1 的磁盘快照与事件回放、P9 的订单持久化尚须实现，不能宣称重启后存在真实物品/经济恢复。

## 后续变更流程

先新增 schemaVersion 与迁移测试，再修改写出版本；保留旧输入回归 fixture，拒绝未知字段和未来版本。破坏确定性的引擎更新必须提升 engine version，旧回放交给对应版本验证器或明确报告不支持。公开 API 改签名须说明消费者、线程边界、预算、迁移与合同测试。
