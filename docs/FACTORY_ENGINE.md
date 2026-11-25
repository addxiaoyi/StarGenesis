# P6 四人虚拟工厂核心 v2

`FactoryWorld` 位于 `stargenesis-sim`，固定四个不同 `AgentId`，所有状态归构造线程拥有。它提供模拟订单竞价、共同贡献组件、赢家交付、生产配方和虚拟工位，不读取 Bukkit、Minecraft 世界、玩家真实背包或经济插件。

## 订单与账本

每个成员以有限初始库存加入，团队 treasury 持有有限模拟信用。创建订单时，创建者指定所需组件数、最高奖励、竞价时长和交付窗口；奖励先从 treasury 转入订单 escrow。订单最多 64 个，交付需求不超过四名成员的初始组件总量。

每位成员对一个订单只能提交一个总价，价格不能超过托管奖励。四人都竞价时立即结算，否则在竞价截止步结算。最低价格中标，同价按 `AgentId` 的稳定字典序决胜。无人竞价的订单取消，奖励退回 treasury。

授标后，任一成员可以把组件转入订单共享缓冲区。只有中标者能交付；缓冲量达到订单需求后，所需组件转入虚拟客户账户，中标者获得报价，奖励余款退回 treasury。超出需求的组件按成员 ID 顺序分配消耗量并退回贡献者。交付窗口结束仍未交付时，订单失败，托管奖励和全部组件贡献返还。

模拟信用与组件/原料分别由账本和工厂质量平衡校验。每名成员初始获得 24 个组件、8 个矿石和 16 个聚合物。矿石质量权重为 2，聚合物和组件权重为 1。配方构造时验证输入质量等于输出组件量；生产完成在单个检查点状态变更中转化原料，不额外铸造物质。订单创建不铸造奖励，贡献/交付只在虚拟账户间转移。玩家输入使用 `(world, tick, member-index, action-sequence)` 生成证据键；重复贡献和生产操作不会重复扣款或排队，证据历史不会逐出。竞价平局、订单遍历、退款和库存分配都使用稳定顺序。

## 配方与工位

每名成员有一个持久虚拟工位，同一时刻最多运行一个有限批次任务。`factory:frame` 每批消耗 1 矿石和 2 聚合物，经过 2 tick 产出 4 组件；`factory:plate` 每批消耗 1 矿石和 1 聚合物，经过 1 tick 产出 3 组件。每条配方最多排入 8 批。排队会先把原料移入工位，取消会退回原料，完成后组件留在工位库存，领取时才转入成员虚拟库存。

```text
/genesis factory station status
/genesis factory station queue <recipe-id> <action-sequence> <batches>
/genesis factory station cancel <action-sequence>
/genesis factory station collect <action-sequence> <amount>
```

`station status` 显示成员原料、工位库存、运行配方和完成 tick。排队/取消结果按成员动作证据持久化；失败材料检查同样占用动作序号，重试必须复用原参数。逻辑步完成配方后再推进工厂状态，工位任务在检查点内恢复。

## 检查点

`FactoryCheckpoint` 包含四人场景设置、tick、订单与竞价状态、每位贡献者的数量、完整资源账本、转账幂等历史、竞价与生产证据、工位任务和状态哈希。`FactoryCheckpointCodec` 使用 magic、格式版本 2、严格有界字段及 UTF-8 编码，最大 2 MiB；恢复会验证成员、订单 escrow、共享缓冲区、原料质量平衡、工位任务和证据归属。v1 文件先用旧字段计算 SHA-256 状态哈希，再迁移成 v2；迁移工厂不会获得新版本的初始原料。

Paper 命令服务只维护编码后的恢复值，每个变更先恢复到临时 `FactoryWorld` 执行，再编码校验并写入 `<plugin-data>/factory-v1.checkpoint`。存储先强制刷新同目录临时文件，再进行原子替换；文件系统不支持原子移动或保存失败时，内存中的已提交状态仍不变。服务启动时拒绝超限、截断或哈希无效的检查点，不会自动创建替代工厂。所有命令动作在 Paper/Folia 全局调度器上串行处理，检查点 I/O 同步执行且单文件上限为 2 MiB。

自动化存储测试注入原子替换前的 I/O 失败，验证上一个已提交检查点仍可读取且失败写入的临时文件已清理。该测试不覆盖操作系统崩溃、断电或文件系统违反原子替换承诺的情况。

玩家通过 `/genesis factory identity` 查看 UUID。组织所有者可先创建开厂前名册并管理成员：

```text
/genesis factory org create <name>
/genesis factory org menu
/genesis factory org status
/genesis factory org add <uuid>
/genesis factory org remove <uuid>
/genesis factory create
```

组织名是最多 32 个字符的小写 ID。创建者自动成为所有者，名册最多四人，所有者不能移除成员资格。只有所有者可以调整名册或从完整名册创建工厂。`org menu` 显示成员和在线可邀请玩家，开厂前所有者可点击加入或移除；命令仍可按 UUID 管理离线成员。Paper/Folia 上的菜单操作经过服务器服务复核所有者身份，并使用与命令相同的全局串行调度和原子保存。名册以 SHA-256 校验的有界二进制检查点原子保存到 `factory-organization-v1.checkpoint`，启动时拒绝损坏文件。工厂创建后名册冻结，避免替换成员破坏账本账户、订单历史和恢复哈希。原有 `/genesis factory create <uuid-1> <uuid-2> <uuid-3> <uuid-4>` 仍可在没有组织名册时使用。每个服务器只允许一个工厂。默认每名成员有 24 个虚拟组件，treasury 有 1,000 虚拟信用，场景最多运行 72,000 tick 并保存最多 64 个订单。

```text
/genesis factory status
/genesis factory orders [page]
/genesis factory order <id> <components> <reward> <auction-ticks> <delivery-ticks>
/genesis factory bid <id> <action-sequence> <price>
/genesis factory contribute <id> <action-sequence> <amount>
/genesis factory fulfill <id>
/genesis factory advance <expected-tick> <1-100>
```

## 跨服成员操作

一个服务器持有组织名册和唯一的 `factory-v1.checkpoint`。受信 peer 上的四人名册成员可以用 `/genesis factory remote <server-id> <factory-command...>` 在目标服务器查询或操作该工厂，例如：

```text
/genesis factory remote hub_a status
/genesis factory remote hub_a orders 2
/genesis factory remote hub_a bid factory:frame 12 20
/genesis factory remote hub_a station queue factory:plate 13 2
```

远程允许 `status`、`orders [page]`、`bid`、`contribute`、`fulfill`、`advance` 和 `station` 操作。工厂创建、订单创建及组织名册管理仍须在工厂所属服务器执行。目标服务器再次核对玩家 UUID 是否属于四人名册，所有虚拟资源仍只存在该工厂的唯一账本中。

此通道复用 [P4 代理配置](PROTOCOL_ENGINE.md#跨服务器代理) 的 BungeeCord 兼容转发和 peer 专属 HMAC 密钥。两端须启用 `protocol-relay`、互相列为受信 peer，并单独设置 `factory-relay.enabled: true`；协议私信 relay 保持关闭时，不能启动工厂 relay。客户端不需要协议芯片。源服务器持久保存请求并每 5 秒重试，目标服务器在执行前持久登记请求 ID，执行后保存响应。重复请求会返回已保存响应；相同请求 ID 携带不同命令会被拒绝。源发件箱和目标回执各有 4,096 条上限，容量耗尽时拒绝新请求且不逐出旧去重记录。插件消息通过在线玩家承载，因此两端都需要在线玩家以发送请求和响应。

HMAC 证明数据包来自配置的 peer 服务器，不提供个人玩家签名。源端只从有 `stargenesis.factory` 权限的在线玩家命令生成请求，目标端再次核对该 UUID 属于工厂名册；配置 peer 的管理员仍位于跨服身份信任边界内。

```yaml
factory-relay:
  enabled: true
```

每个远程请求先在临时模拟副本执行，再将请求 ID、命令摘要、原检查点摘要、完整目标检查点和响应写入 `factory-relay/transaction-v1.checkpoint`。事务日志有大小上限和 SHA-256 校验，先刷盘并原子提交，再保存工厂检查点和完成回执，最后删除事务日志。启动及每个后续工厂命令首先恢复未完成事务。恢复只接受原检查点或已提交目标检查点，状态冲突会阻止继续修改。完成回执按请求 ID 返回原响应，工厂 tick 后续变化也不会重放已完成的资源操作。

2026-11-24 故障注入测试覆盖工厂检查点写入失败、检查点成功但回执写入失败、恢复后推进 tick、状态冲突和日志损坏。已有 v1 工厂、组织和回执格式继续读取。旧版 `PENDING` 回执缺少事务日志时无法判定副作用是否已提交，自动重试返回需要管理员核查的请求 ID。

`fulfill` 本身也支持恢复后重复调用：已交付且调用者仍是中标者时返回成功，不再执行账本转账。`FactorySessionTest.fulfillmentRetryAfterCheckpointRecoveryDoesNotChangeTheLedgerAgain` 覆盖了检查点恢复、重放交付和检查点不变断言。该单元测试验证模型层恢复行为，不能替代进程在 Paper 持久写入与 relay 回执写入之间被强制终止的验收。

竞价/贡献/工位操作命令要求每个成员提供 0 到 250,000,000 范围内的未使用 `action-sequence`。同一 tick 使用相同序号和参数可安全重试，参数冲突会被拒绝；推进 tick 后相同序号代表新动作。推进命令要求填写当前预期 tick，保存成功后的重试会因 tick 不匹配而拒绝，避免重复推进。逻辑步由成员手动推进，不依赖后台计时任务。

最多保存 64 个订单、每个工厂最多 4,096 条账本尝试、4,096 条首次生产证据及 256 条首次竞价证据。自动结算、原料转移和取消会在写入前检查完整证据容量与序号预算，容量耗尽时操作失败且不留下部分退款或付款。

## P6 尚缺

当前纵向实现覆盖开厂前组织创建/成员变更与持久名册、开厂后冻结、共享订单竞价、虚拟组件交付、生产配方与工位、组织 Inventory 管理菜单、Paper/Folia 命令入口、单服务器原子检查点及经受信 peer 转发的跨服成员操作。离线 Mineflayer 客户端已在 Paper/Folia 验证组织菜单的权限、成员加入/移除、所有者保护和名册冻结，也验证两种配方、手动推进、工位领取、正常停服重启和确认保存后的强制进程终止恢复，证据见 `docs/evidence/p6/paper-production-result.json` 与 `folia-production-result.json`。Java 21 测试覆盖完整 72,000 tick 场景并每 10,000 tick 及终点执行编码/恢复，共 8 次，状态哈希和质量账本一致。2026-11-24 Paper/Folia 双后端、Velocity 3.5.1-615 和四名离线客户端各通过 11 项 peer relay 检查，证据见 `docs/evidence/p6/paper-proxy-transaction-03-20261124.json` 与 `folia-proxy-transaction-01-20261124.json`。仍缺真人四人联机、机器断电和提交前后故障验收。模拟信用、组件与原料不能兑换真实库存或经济余额，P6 仍处于开发中。
