# StarGenesis · 创世沙盒

**玩家发明规则，世界检验发明。**

换设备继续开发：先读 [开发交接与阶段进度（2026-11-25）](docs/DEVELOPMENT_HANDOFF.md)，包含当前验证结果、新设备构建命令和下一步任务。

原创 Minecraft 创作与确定性模拟插件。长期目标是让玩家组合结构、有限行为图、通信协议、组织和规则创作新玩法。当前为 **4.0.0-SNAPSHOT / P0 基础验收通过，P1-P11 开发中**。已有确定性网格实验室、玩家蓝图与行为图入口、有界通信核心、守恒生态实验室及跨玩家共享生态组命令和 Inventory、四人虚拟工厂命令入口，经过审核的竞技规则发布、共享多人房间、Ed25519 签名设计交换和 P9 有界 saga 合约与持久日志；真人 Paper/Folia 玩家体验、真实经济适配、反作弊和 P11 发布验收仍在开发。

实际阶段状态见 [docs/PHASE_STATUS.md](docs/PHASE_STATUS.md)，产品/线程/资源契约见 [docs/PRODUCT_CONTRACT.md](docs/PRODUCT_CONTRACT.md)，规则集编辑和发布约束见 [docs/ARENA_RULESETS.md](docs/ARENA_RULESETS.md)，签名设计交换与 P10 限制见 [docs/DESIGN_EXCHANGE.md](docs/DESIGN_EXCHANGE.md)，P9 交易 saga 边界见 [docs/TRANSACTION_SAGAS.md](docs/TRANSACTION_SAGAS.md)，GitHub 发布构建与来源证明见 [docs/RELEASING.md](docs/RELEASING.md)。

## 构建

需要 Java 21+ 和网络；Maven Wrapper 固定 Maven 3.9.11 并校验分发包 SHA-256。

```sh
./mvnw -B verify
python scripts/check_boundaries.py
```

Windows 使用 `mvnw.cmd -B verify`。产物：`stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar`；安装此 shaded JAR，不要安装 `original-*` 或 api/sim 等独立库 JAR。Paper API 固定 `1.21.11-R0.1-20260511.115010-91`；未把 Bukkit/Paper 或第三方插件打入发布物。

## 安装与操作

复制插件 JAR 到 Paper 1.21.11 的 plugins，启动服务器。首次生成 config.yml；插件启动时校验预算、配置版本和五份内置设计文档。未知配置或 `integrations.allow-real-settlement: true` 会拒绝启动。实验室星屑仅存在于模拟检查点，不会进入真实背包或货币系统；模拟按玩家操作执行，没有后台模拟线程。

| 命令 | 权限 | 默认 |
|---|---|---|
| `/genesis help` | stargenesis.help | 所有人 |
| `/genesis status` | stargenesis.status | 所有人 |
| `/genesis lab` | stargenesis.lab | 所有人 |
| `/genesis lab reset` | stargenesis.lab | 所有人 |
| `/genesis lab close` | stargenesis.lab | 所有人 |
| `/genesis blueprint` | stargenesis.blueprint | 所有人 |
| `/genesis blueprint reset` | stargenesis.blueprint | 所有人 |
| `/genesis program` | stargenesis.program | 所有人 |
| `/genesis program reset` | stargenesis.program | 所有人 |
| `/genesis program run` | stargenesis.program | 所有人 |
| `/genesis protocol` | stargenesis.protocol | 所有人 |
| `/genesis protocol send <player> <sequence>` | stargenesis.protocol | 所有人 |
| `/genesis protocol relay <server> <player-uuid> <sequence>` | stargenesis.protocol | 所有人 |
| `/genesis protocol inbox` | stargenesis.protocol | 所有人 |
| `/genesis protocol inbox clear` | stargenesis.protocol | 所有人 |
| `/genesis protocol reset` | stargenesis.protocol | 所有人 |
| `/genesis ecosystem` | stargenesis.ecosystem | 所有人 |
| `/genesis ecosystem reset` | stargenesis.ecosystem | 所有人 |
| `/genesis ecosystem share list [page]` | stargenesis.ecosystem | 所有人 |
| `/genesis ecosystem share create` | stargenesis.ecosystem | 所有人 |
| `/genesis ecosystem share add <group-id> <player-uuid>` | stargenesis.ecosystem | 所有人，组所有者 |
| `/genesis ecosystem share remove <group-id> <player-uuid>` | stargenesis.ecosystem | 所有人，组所有者 |
| `/genesis ecosystem share leave <group-id>` | stargenesis.ecosystem | 所有人，组成员 |
| `/genesis ecosystem share disband <group-id>` | stargenesis.ecosystem | 所有人，组所有者 |
| `/genesis ecosystem share status <group-id>` | stargenesis.ecosystem | 所有人，组成员 |
| `/genesis ecosystem share step <group-id>` | stargenesis.ecosystem | 所有人，组成员 |
| `/genesis factory ...` | stargenesis.factory | 所有人 |
| `/genesis arena` | stargenesis.arena | 所有人 |
| `/genesis arena reset` | stargenesis.arena | 所有人 |
| `/genesis arena publish` | stargenesis.arena | 所有人，提交管理员审核 |
| `/genesis arena releases` | stargenesis.arena | 所有人 |
| `/genesis arena review` | stargenesis.arena.review | OP |
| `/genesis arena decisions [page]` | stargenesis.arena.review | OP，玩家与控制台只读查询审核历史 |
| `/genesis arena decision <sha256>` | stargenesis.arena.review | OP，查看签名证据及实际发布状态 |
| `/genesis arena recover <sha256> <reviewer-uuid> <signature>` | stargenesis.arena.review | OP，玩家或控制台重放已持久化的签名批准 |
| `/genesis arena approve <sha256> <signature>` | stargenesis.arena.review | OP，需审核者 Ed25519 签名 |
| `/genesis arena reject <sha256> <signature> <reason>` | stargenesis.arena.review | OP，需审核者 Ed25519 签名 |
| `/genesis arena grant <uuid>` | stargenesis.arena | 所有人 |
| `/genesis arena revoke <uuid>` | stargenesis.arena | 所有人 |
| `/genesis arena map` | stargenesis.arena | 所有人 |
| `/genesis arena play` | stargenesis.arena | 所有人 |
| `/genesis arena rooms` | stargenesis.arena | 所有人 |
| `/genesis arena join <room> <team:slot>` | stargenesis.arena | 所有人 |
| `/genesis arena ready <room>` | stargenesis.arena | 所有人，房主 |
| `/genesis arena act <room> <tick> <action>` | stargenesis.arena | 所有人，已入座玩家 |
| `/genesis arena match <room>` | stargenesis.arena | 所有人 |
| `/genesis arena leave <room>` | stargenesis.arena | 所有人，房主除外 |
| `/genesis arena kick <room> <uuid>` | stargenesis.arena | 所有人，房主 |
| `/genesis arena stop <room>` | stargenesis.arena | 所有人，房主 |
| `/genesis settlement list [page]` | stargenesis.settlement.review | OP |
| `/genesis settlement inspect <saga-uuid>` | stargenesis.settlement.review | OP |
| `/genesis design keys` | stargenesis.design.admin | OP |
| `/genesis design trust <author-uuid> <base64-ed25519-key>` | stargenesis.design.admin | OP |
| `/genesis design revoke <author-uuid>` | stargenesis.design.admin | OP |
| `/genesis design reviewers list` | stargenesis.design.admin | OP |
| `/genesis design reviewers trust <reviewer-uuid> <base64-ed25519-key>` | stargenesis.design.admin | OP |
| `/genesis design reviewers revoke <reviewer-uuid>` | stargenesis.design.admin | OP |
| `/genesis design import <file-key>` | stargenesis.design.admin | OP |
| `/genesis design imports [page]` | stargenesis.design.admin | OP |
| `/genesis design inspect <artifact-sha256>` | stargenesis.design.admin | OP |
| `/genesis debug capabilities` | stargenesis.debug | OP |
| `/genesis debug budgets` | stargenesis.debug | OP |

实验室在 Minecraft Inventory 中展示局部网格。点击方向、采集、存入或等待按钮推进一个逻辑步。玩家检查点使用 Paper PersistentDataContainer 保存；`reset` 会替换该玩家的实验状态。

蓝图编辑器提供模块放置、移动、旋转、删除、连接、断开、撤销、预算预览及签名保存，编辑草稿和撤销历史保存在玩家检查点。`/genesis program` 打开行为图编辑器，玩家从有限 typed IR 操作目录选择节点，连接类型匹配的输入，轮换传感器、方向和比较模板，撤销、编译预览并签名保存。编辑草稿和撤销历史保存在玩家检查点。保存的程序可通过 `/genesis program run` 在私人实验室对当前玩家快照使用有界解释器运行，调试跟踪显示节点结果，动作进入同一确定性网格步。

`/genesis arena` 打开规则编辑器。schema 2 支持障碍格、目标格、红蓝队出生位、`ANY`/`ALL` 胜利组合、控点数和目标积分。作者可切换公开读取及克隆许可，权限变化自动生成新补丁版本。`/genesis arena map` 打开 5 × 3 地图视口，点击元素按钮切换障碍、目标或队伍出生位，方向按钮移动视口。旧 schema-1 草稿可用编辑器升级控制迁移为 schema 2，升级保留原哈希记录并生成新补丁版本。

预览会检查地图边界、重叠、出生位与目标路径连通以及回合工作量。预览后保存，再用 `/genesis arena publish` 提交管理员审核。管理员用 `/genesis arena review` 查看队列，使用完整 SHA-256 哈希执行 `approve` 或附带原因执行 `reject`。审核决定、候选和不可变发布版本保存在服务器数据目录。作者可用 `/genesis arena grant <uuid>` 与 `revoke <uuid>` 更新编辑权限草稿，授权仍须经审核发布后生效。依赖固定到已发布的 id、版本和哈希。

`/genesis arena play <design-id> <version>` 可用已发布且允许公开读取的 schema-2 规则创建房间；无参数的 `play` 使用玩家自己的精确已发布版本。内置规则可用 `/genesis arena play genesis:arena_ruleset 1.1.0` 直接开局。玩家通过 `/genesis arena rooms [page]` 按页浏览房间，再用房间 UUID 和 `red:1` 等席位加入。每个席位都须有不同玩家，两个队伍都要有人入座，房主用 `ready <房间>` 开始。玩家用 `/genesis arena act <房间> <tick> <action>` 为自己的席位提交 `wait`、`north`、`east`、`south` 或 `west`。动作同时结算，争抢同一格的单位原地等待。缺席玩家的动作在 30 秒后按等待处理。座位、待处理动作、进度和积分先通过校验，再原子保存到服务器数据目录；服务器重启后同一 UUID 可回到自己的座位继续对局。`match <房间>` 查看快照，`spectate <房间>` 查看只读地图和比分，房主可用 `stop <房间>` 删除房间和终局存档。服务器最多保存 256 个房间，达到上限时需由房主停止已结束的对局。竞技只使用虚拟地图，不修改 Minecraft 方块、背包、领地或真实货币。

能力报告分别显示插件安装/启用、审计版本、API验证、Paper证据及逐项能力。安装插件不会自动激活未验证桥接；报告是启动时快照，配置/依赖更改后重启。StarCore、helsteraMobs 与 SenLuo/CraftEngine 的具体限制见 [docs/INTEGRATION_AUDIT.md](docs/INTEGRATION_AUDIT.md)。

`/genesis protocol` 可解锁或装备公开入门芯片。双方在线并装备兼容芯片后，`send` 会发送协议定义的整数序号载荷。配置 BungeeCord 兼容代理和双方 peer 密钥后，`relay` 可向另一后端的在线玩家发送同一协议消息。`inbox` 显示已写入玩家 PDC 的消息，配置方法和保存边界见 [docs/PROTOCOL_ENGINE.md](docs/PROTOCOL_ENGINE.md)。

`/genesis ecosystem` 打开个人封闭生态。生态运行在确定性的 8×8 栖息地中，营养、废料和个体位置按格记录；个体就地摄取，按相邻格食物供给移动，并受格内密度繁殖上限约束。每个逻辑步按稳定顺序执行有限质量平衡反应、营养桥接、分解、摄取、代谢、生存筛选和繁殖；有限基因参数可发生确定性单步突变。选择个体后可调整摄取效率、代谢、寿命和成熟期，取值始终受限。配方工坊每侧提供至多 16 个物质槽，可编辑复合输入/输出、调整计量系数与每步触发上限；保存时按物种合并重复项并验证质量平衡。生成的 `ecology:nutrient` 会进入生物摄取池。营养、废料、生物量和反应库存组成的物质账本保持不变；可用能量、个体储能和散失热组成独立能量账本，外部能量输入逐步计入。统计面板显示两类账本、种群、基因型数量和状态哈希。v4 有界检查点保存全部栖息地格、个体位置、反应定义及库存到玩家 PDC，并兼容迁移 v1、v2、v3 存档；旧 v1 存档以当前个体储能建立新的能量基线。`ecosystem reset` 重建个人生态。共享组通过 `ecosystem share menu` 打开 Inventory，或用 `share create` 创建、`list [page]` 找回本人所属组；所有者可在菜单中邀请或移除在线成员，也可用 `add <group-id> <player-uuid>` 邀请离线成员。每位成员都可在 Inventory 查看组状态并共同推进逻辑步。组最多 8 人、服务器最多保存 128 个组，所有者可移除成员或经二次确认解散组，成员可自行离开。共享检查点以服务端文件原子替换保存，私人生态仍保存于各自玩家 PDC。Paper/Folia 三个离线自动化客户端覆盖组管理菜单、共同步进和正常重启恢复，证据见 `docs/evidence/p5/shared-ecology-paper.json` 与 `shared-ecology-folia.json`；这不替代真人体验或长周期保存耐久性评估。命令约束、存储格式与验收缺口见 [docs/ECOLOGY_ENGINE.md](docs/ECOLOGY_ENGINE.md)。

`/genesis factory identity` 显示玩家 UUID。组织所有者可用 `/genesis factory org create <name>` 创建名册，通过 `org add <uuid>` 和 `org remove <uuid>` 在开厂前调整成员，最多四人且所有者不可移除。`org status` 显示名册，`org menu` 打开组织 Inventory，可浏览成员和在线可邀请玩家。四人到齐后，所有者执行 `/genesis factory create` 开厂，名册随即冻结并用于订单模拟。没有组织名册时，也可用旧式 `/genesis factory create <uuid-1> <uuid-2> <uuid-3> <uuid-4>` 建厂。每台服务器只有一个虚拟工厂，支持 `status`、`orders`、`order`、`bid`、`contribute`、`fulfill`、`advance` 和 `station` 子命令。成员在每个逻辑 tick 内为竞价、贡献和工位操作提供未使用的 action-sequence，服务在 Paper/Folia 全局调度器串行处理，并在每次变更前验证检查点、原子写入插件数据目录。工厂有质量平衡的 `factory:frame` 与 `factory:plate` 两种配方，每名成员有独立持久工位，可用 `/genesis factory station status` 查看状态、`station queue <recipe-id> <action-sequence> <batches>` 排队、`station cancel <action-sequence>` 取消和 `station collect <action-sequence> <amount>` 领取。名册文件上限为 512 字节，工厂检查点上限为 2 MiB。逻辑 tick 通过 `/genesis factory advance <expected-tick> <1-100>` 手动推进，保存成功后的重复命令会因预期 tick 变化而拒绝。竞价、贡献或工位操作重试时复用原 action-sequence 与参数。完整命令和状态约束见 [docs/FACTORY_ENGINE.md](docs/FACTORY_ENGINE.md)。工厂组件、原料与信用始终是模拟余额。

P6 跨服成员可使用 `/genesis factory remote <server-id> <factory-command...>` 查询或操作工厂所属服务器的账本，包括查单、竞价、贡献、交付、推进和工位操作。两端须启用 `protocol-relay`、互配 peer 密钥并单独设 `factory-relay.enabled: true`，代理需支持 BungeeCord 插件消息；创建工厂、创建订单和组织管理仍在工厂所属服务器执行。远程操作复用同一个工厂检查点和虚拟账本，相关边界见 [docs/FACTORY_ENGINE.md](docs/FACTORY_ENGINE.md) 与 [docs/PROTOCOL_ENGINE.md](docs/PROTOCOL_ENGINE.md)。Paper/Folia 双后端与 Velocity 3.5.1-615 的离线代理验收已经完成，各 11 项通过，仍待真人四人联机、机器断电及提交前后故障验收。

个人实验室和生态面板只操作命令玩家自己的 Inventory 与 PersistentDataContainer，不访问 Minecraft 世界。共享生态 Inventory 在玩家线程显示服务端共享组快照，状态读写由 Paper/Folia 全局调度器串行处理。Paper/Folia 离线客户端流程已验证共享组菜单和保存恢复，真人体验及 v4 PDC 长周期耐久性仍待评估。

## 真实服务器测试

提供独立目录，避免接触生产世界；脚本绑定 localhost，生成最小测试世界，执行命令并停服，输出原始控制台日志、机器可读结果和工件哈希。

```sh
python scripts/server_smoke.py --server-jar /path/paper.jar --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --directory .work/server-paper --accept-eula
python scripts/server_smoke.py --server-jar /path/folia.jar --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --directory .work/server-folia --kind folia --accept-eula
```

`--accept-eula` 仅在接受 Mojang EULA 时使用；也可预先提供已接受的 eula.txt。可用 `--java /path/java` 指定运行时。测试只证明真实服务器控制台生命周期，不证明真人客户端、模型、跨区域操作或第三方经济兼容。

`server_smoke.py` 默认检查尚未建厂的命令响应。使用 `--factory-mode create` 可从服务器控制台建立固定测试名册，随后在相同目录使用 `--factory-mode recover` 检查重启恢复；`empty`、`create`、`recover` 都只触及脚本指定的隔离目录。

蓝图编辑器保存已验证蓝图后，可使用 /genesis assemble 在私有虚拟装配台逐件搭建。界面显示蓝图的 3D 坐标、朝向、模块和已形成连接，进度保存在玩家检查点。该练习不会生成 Minecraft 方块或改变真实背包。使用 /genesis assemble reset 可从当前保存蓝图重新开始。

`DesignExchange` 提供 Ed25519 签名的有界 `.sgdesign` 交换格式。设计作者公钥由管理员按作者 UUID 固定；导入的竞技规则进入管理员审核队列，蓝图、程序、协议和合约以原签名包原子归档。管理员可用 `/genesis design imports [page]` 浏览归档，并用 `/genesis design inspect <artifact-sha256>` 查看单项元数据和签名指纹。将签名文件放入 `plugins/StarGenesis/design-imports/`，管理员使用 `/genesis design trust` 注册作者公钥后运行 `/genesis design import <file-key>`。归档设计不会自动启用或执行。信任列表、轮换和撤销流程见 [docs/DESIGN_EXCHANGE.md](docs/DESIGN_EXCHANGE.md)。

## 工程结构与后续开发

五个有实现模块：api（稳定契约）、design（格式/安全校验/Ed25519 签名交换/迁移）、sim（确定性网格、封闭生态、四人虚拟工厂、质量平衡反应网络、守恒账本、outbox、检查点与回放）、integrations（能力发现及 P9 settlement saga 契约）、paper（插件宿主和签名发布者信任管理）。网格、生态和工厂核心分别见 [docs/GRID_ENGINE.md](docs/GRID_ENGINE.md)、[docs/ECOLOGY_ENGINE.md](docs/ECOLOGY_ENGINE.md) 和 [docs/FACTORY_ENGINE.md](docs/FACTORY_ENGINE.md)。质量平衡反应网络已接入生态逻辑步、营养摄取、守恒物质统计、状态哈希和 v4 检查点；玩家配方工坊支持每侧最多 16 个物质槽的复合反应，生态已有确定性 8×8 栖息地、局部摄取/分解、供给驱动移动和格内密度反馈。跨玩家生态组已有命令、持久名册、共享 Inventory、状态查询和共同步进；Paper/Folia 三离线客户端流程各验证 28 项组管理与权限检查、正常重启恢复和个人 v4 PDC 两次正常重启恢复，证据见 `docs/evidence/p5/shared-ecology-paper.json` 与 `shared-ecology-folia.json`。真人协作体验和长时间/异常保存耐久性仍未完成。四人工厂有 Paper/Folia 命令入口、模拟订单竞价、虚拟交付、开厂前持久组织名册、组织 Inventory 管理界面、质量平衡生产配方、每成员持久工位、单服务器原子检查点及经受信 peer 转发的成员命令；Paper/Folia 离线 Mineflayer 客户端验证菜单权限、成员增删、冻结状态及确认保存后的进程终止恢复。72,000 tick 仿真与八次检查点恢复保持状态哈希和质量账本一致。Paper/Folia 双后端与 Velocity 3.5.1-615 的离线代理流程各 11 项检查通过；真人四人体验、机器断电和提交前后故障验证仍未完成。JSON Schema 和签名哈希示例在 design 模块资源目录；SHA-256 为完整性校验，不是作者数字签名。

每次变更执行正式 verify，新增公共接口须有合同测试。阶段门、缺口和实际证据持续记入 docs；后续实现不得把模拟数量兑付真实物品，不得用 Mock 冒充实机验收。
