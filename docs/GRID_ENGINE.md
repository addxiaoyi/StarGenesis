# P1 网格引擎 v1

`stargenesis-sim` 中的 `GridSimulationWorld` 是线程归属的纯 Java 实现，不访问 Bukkit、墙钟或真实库存。世界尺寸最多为 64×64，总计不超过 4096 格；单个世界的 Agent 数量受 `SimulationBudget.maxAgents` 限制。

## 初始世界

格子按 y 优先、x 次序展平成索引 `i`。地形与资源使用固定 SplitMix64 finalizer，所有运算均为 Java `long` 溢出回绕：

- `mix64(v)` 先执行 `v += 0x9e3779b97f4a7c15`，随后依次执行 `(v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9`、`(v ^ (v >>> 27)) * 0x94d049bb133111eb`，最后返回 `v ^ (v >>> 31)`。
- 地形值为 `mix64(seed ^ 0x6a09e667f3bcc909 ^ (i * 0x9e3779b97f4a7c15))`。当 `floorMod(value, 29) == 0` 时该格不可通行。
- 资源值为 `mix64(seed ^ 0xbb67ae8584caa73b ^ (i * 0xd1b54a32d192ed03))`。当地形可通行且 `floorMod(value, 13) == 0` 时生成 `1 + floorMod(value >>> 8, 3)` 个 `material:star_shard`。
- 每个初始 Agent 所在格强制可通行，并至少有 1 个星屑。该数量属于场景初始资源。

## 动作

每个 `StepInput.Command` 使用 Agent ID 和 sequence 排序，同一格冲突按该顺序解决。一次输入步完成后逻辑 tick 加一，即使没有命令也一样。每条命令对应同 tick 的 `action.applied` 或 `action.rejected` 事件，事件 sequence 是本步命令的规范顺序。

| action | arguments | 行为 |
|---|---|---|
| `move` | `dx`、`dy` | 只接受相邻的四个正交方向；拒绝越界、障碍格和已占用格 |
| `gather` | 空 | 从 Agent 当前格采集 1 个星屑到模拟背包 |
| `deposit` | 正整数 `amount` | 将模拟背包中的星屑转入世界共享库存 |
| `wait` | 空 | 不改变位置或资源 |

其他动作、无效参数、未知 Agent 和资源不足均产生拒绝事件，不执行部分资源变更。世界资源总量是格子资源、Agent 背包与共享库存之和，四种动作保持总量不变。共享库存与真实物品分离。

## 快照与回放

快照按行优先列出所有格子，Agent 按 `AgentId` 排序，并防御性复制集合。检查点只包含当前网格状态和恢复所需设置。内存回放包含检查点基线、逐步输入和完整 `StepResult`；`GridSimulationWorld.replay` 会从基线执行并比较事件、tick、终止信息及 SHA-256 状态哈希。玩家实验室 PDC 使用 GridWorldReplayCheckpointCodec 保存版本化的基线、当前检查点和逐步 StepInput，解码时确定性重放并比较全部当前快照。玩家实验室回放最多 4096 条命令且总编码最多 1 MiB，旧版只含单检查点的玩家数据可继续读取。PDC 内容在 Paper 玩家数据的最终保存时点落盘，插件不声称绕过该平台保存周期。

状态哈希使用 `DataOutputStream` 大端编码并以 UTF-8 长度前缀编码文本。字段依次为网格引擎版本、world ID、seed、尺寸、步长毫秒、全部模拟预算、初始 Agent 数和按 AgentId 排序的 spawn 项、tick、共享库存、按行优先排列的 `width × height` 个 `(blocked, resourceUnits)`，Agent 数，以及按 AgentId 排序的 `(UUID most-significant bits, UUID least-significant bits, x, y, inventory)`。

检查点是不可变值对象，由 `GridWorldCheckpointCodec` 以有界版本化二进制格式编码。Paper 实验室把检查点写入玩家 `PersistentDataContainer`，每次逻辑步写一次；最终保存时点依赖 Minecraft 玩家数据生命周期。回放记录仍是内存值对象，玩家界面目前没有跨操作回放日志。单条回放最多包含 65,536 条命令。引擎语义变化须提升 `grid/1` 版本并保留旧版本验证器，不能静默改变已有回放结果。
