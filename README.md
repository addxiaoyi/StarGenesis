# StarGenesis · 创世沙盒

**玩家发明规则，世界检验发明。**

原创 Minecraft 创作与确定性模拟插件。长期目标是让玩家组合结构、有限行为图、通信协议、组织和规则创作新玩法。当前为 **4.0.0-SNAPSHOT / P0 基础验收通过，P1–P8 服务端纵向代码闭环已实现，玩家验收仍在推进**，尚未交付通过玩家验收的完整实验或竞技玩法。

实际阶段状态见 [docs/PHASE_STATUS.md](docs/PHASE_STATUS.md)，产品/线程/资源契约见 [docs/PRODUCT_CONTRACT.md](docs/PRODUCT_CONTRACT.md)。

## 构建

需要 Java 21+ 和网络；Maven Wrapper 固定 Maven 3.9.11 并校验分发包 SHA-256。

```sh
./mvnw -B verify
python scripts/check_boundaries.py
```

Windows 使用 `mvnw.cmd -B verify`。产物：`stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar` 与 reactor aggregate CycloneDX 1.6 SBOM `target/sbom.json`；安装此 shaded JAR，不要安装 `original-*` 或 api/sim 等独立库 JAR。Paper API 固定 `1.21.11-R0.1-20260511.115010-91`；未把 Bukkit/Paper 或第三方插件打入发布物。

## Release Signing

发行者可使用 JDK 21 对最终版本插件 JAR 与 SBOM 生成 Ed25519 签名清单。签名私钥保存在仓库之外，发行产物与 manifest 放在同一目录：

```sh
java scripts/ReleaseArtifactSigner.java keygen <private.pem> <public.pem>
java scripts/ReleaseArtifactSigner.java sign <private.pem> <publisherKeyId> <4.0.0> <release-manifest.txt> <StarGenesis.jar> <sbom.json>
java scripts/ReleaseArtifactSigner.java verify <public.pem> <release-manifest.txt>
```

清单包含有序工件文件名、大小和 SHA-256，验证会检查 Ed25519 签名及目录内实际文件；SNAPSHOT 版本不会签署。工具自测只使用一次性密钥，不表示正式发行已经签署。

可先在隔离副本构建非 SNAPSHOT RC，并运行完整测试与 SBOM：

```sh
python scripts/build_release_candidate.py --version 4.0.0-rc.1 --java java
```

候选 JAR、SBOM 与构建日志写入 `.work/releases/<version>/`，来源树哈希与测试汇总写入 P11 证据。可将候选 JAR 传给 `scripts/server_smoke.py`，分别在隔离 Paper 与 Folia 目录验证启动和退出。RC 构建不会修改工作树版本，也不会生成运营方发行签名。

## 安装与操作

复制插件 JAR 到 Paper 1.21.11 的 plugins，启动服务器。首次生成 config.yml；插件启动时校验预算、配置版本和五份内置设计文档。未知配置或 `integrations.allow-real-settlement: true` 会拒绝启动。P0 没有真实库存/货币转移，也没有后台模拟任务。

| 命令 | 权限 | 默认 |
|---|---|---|
| `/genesis help` | stargenesis.help | 所有人 |
| `/genesis status` | stargenesis.status | 所有人 |
| `/genesis debug capabilities` | stargenesis.debug | OP |
| `/genesis debug budgets` | stargenesis.debug | OP |
| `/genesis lab start [seed] [agents]` | stargenesis.lab | 所有人 |
| `/genesis blueprint` | stargenesis.blueprint | 所有人 |
| `/genesis program` | stargenesis.program | 所有人 |

能力报告分别显示插件安装/启用、审计版本、API验证、Paper证据及逐项能力。安装插件不会自动激活未验证桥接；报告是启动时快照，配置/依赖更改后重启。StarCore、helsteraMobs 与 SenLuo/CraftEngine 的具体限制见 [docs/INTEGRATION_AUDIT.md](docs/INTEGRATION_AUDIT.md)。

P1 实验室还提供 `/genesis lab stop`、`replay`、`resume` 和 `status`。Paper 菜单只显示虚拟模拟状态，不会更改真实世界或发放物品；关闭菜单会停止本局。Folia 使用相同的模拟、检查点和回放，通过命令查看状态并控制实验，不打开玩家背包。P1 尚未通过真人客户端验收。

P2 蓝图工作区提供个人虚拟结构编辑、结构代价预览、撤销和显式保存。常用流程如下：

```text
/genesis blueprint create player:rover
/genesis blueprint add solar genesis:solar_panel 1 0 0 core
/genesis blueprint add engine genesis:thruster -1 0 0 core
/genesis blueprint preview
/genesis blueprint undo
/genesis blueprint save
```

支持 `genesis:core`、`frame`、`solar_panel`、`sensor`、`wheel`、`thruster` 和 `wing` 模块。预览报告质量/承载、材料代价、供能、推力、升力、能力和组件坐标。蓝图以作者 UUID 分目录、以 ID 哈希命名并通过 schema/hash 校验，每位玩家最多保存64份；只允许作者编辑，保存会检查基准哈希，避免覆盖已变化版本。修改只在个人虚拟蓝图中生效，不放置方块或物品。P2 仍需真人客户端验证实际命令、重启恢复和多玩家隔离。

P3 typed-program 工作区提供35个有类型原语、个人程序保存和断点调试：

```text
/genesis program create player:threshold
/genesis program replace start CONSTANT_INTEGER - value=5
/genesis program add result COMPARE_THRESHOLD_INTEGER start operator=gt,value=4
/genesis program breakpoint result on
/genesis program step
/genesis program continue
/genesis program simulate 42 20
/genesis program save
```

使用 `catalog` 浏览原语、`show` 查看指令、`sensor`/`resource`/`signal`/`context` 设置虚拟输入、`run` 在孤立上下文中调试、`simulate` 在确定性资源沙盒中执行并验证 replay。`simulate` 最多运行20个逻辑步，信号只在本次沙盒 session 传递。程序以作者 UUID 隔离并保存 typed-program schema v1；沙盒可消耗模拟能源/资源，不改变 Minecraft 方块、真实库存或货币。P3 仍需通过真人客户端、Folia 生命周期和多人验收。

P4 虚拟无线使用已哈希封存的 `genesis:radio` 与 `genesis:service` 协议。玩家分别注册虚拟网络坐标与范围后，可按 tick 向安装同哈希协议且位于覆盖范围内的玩家发送消息：

```text
/genesis radio chip
/genesis radio join sector_a 0 0 0 16
/genesis radio send <recipient UUID> 1 supply ore_offer
/genesis radio inbox
/genesis radio ack <sender UUID> <message tick> 1
```

Contract 玩家流程使用双方各自的 `genesis:service` 芯片：

```text
/genesis radio chip genesis:service
/genesis radio join sector_a 0 0 0 16
/genesis radio send contract <provider UUID> 1 service_01 offer 3
/genesis radio inbox
/genesis radio send contract <requester UUID> 1 service_01 accept 3
/genesis radio send contract <requester UUID> 2 service_01 fulfill 3
/genesis radio contract service_01
```

有效 service 消息在 `inbox` 中推进模拟 Contract 并确认投递。不符合状态或参与者方向的消息保留在信箱中。合同状态查询只允许请求方或提供方。默认 `genesis:service_contract` 模板为 schema v1、版本 `1.0.0`，超时 200 个逻辑 tick，单位上限 64。

`chip` 主动生成 PDC 绑定当前协议 ID/hash 的纸质知识芯片，`join` 检查玩家背包持有对应芯片。消息下一逻辑 tick 送达。`plugins/StarGenesis/radio-state.json` 以 schema v1 保存消息幂等证据、未确认投递、发送/确认事件、Contract 状态与协议/定义哈希，文件上限 16 MiB。排队、确认、Contract 收件和关服时立即原子保存，逻辑 tick 每 20 步保存一次。启动时校验版本与哈希，文件不兼容会拒绝加载。玩家退出会注销在线端点，重新 join 后可领取仍未确认的消息，服务端重启后也需重新 join，离线期间逻辑 tick 暂停。无线只使用虚拟坐标和玩家 UUID，消息投递不读写 Minecraft 世界、聊天或真实库存。Paper/Folia 五客户端实服流程验证重复 poll、ack 清空、收件人掉线后原 envelope 重投及相同幂等键重发不重复投递；这仍不替代真人长时通信验收。

P5 封闭生态舱使用有限 matter 账本。生产者、食草者和分解者的每个生长、进食、繁殖与死亡都在 soil、detritus 和个体 biomass 账户间转移同一种物质。玩家可调整物种的 intake、繁殖门槛、寿命和突变率，参数在服务器硬上限内生效：

```text
/genesis ecology start 42
/genesis ecology program producer 4 6 48 150
/genesis ecology step 20
/genesis ecology dashboard
/genesis ecology replay
```

`program` 会在当前逻辑步应用新规则。`step` 每次最多推进20步。繁殖会转移已有 biomass，突变只修改有界物种参数，并受32个个体、8代、512次出生和500步总长限制。`dashboard` 显示物质余额、种群、代数、出生/死亡和物种规则。个人 replay 保存在 `plugins/StarGenesis/ecology/<player UUID>.ecology.json`，重载会从 seed 重演输入并核对初始状态、每步状态哈希和事件哈希。`reset` 删除该玩家的生态舱。整个流程只改模拟账本，不访问 Minecraft 世界、背包或经济服务。

Paper/Folia 自动客户端另运行四个 extinction 对照 profile 和固定 seed 52008 candidate，均核对 total matter 与 replay。四个对照 profile 在 SNAPSHOT/RC 的 Paper/Folia 实服运行中均在500步前灭绝；candidate 在四次服务器/版本复现中均达到500步，终态 population 为 producer/grazer/decomposer 26/2/4。纯 Java 引擎另在两个归档 Paper/Folia Alice worldId 上扫描 seed 52008–52107，200/200 均到500 ticks并保留三物种；这是模拟扫描，不是200次服务器运行。2026-11-25 完成 Paper/Folia 各八 seed（52008–52015）自动客户端实服扩展，16/16 均到500 tick、保留三物种、matter=1000，replay 全部通过。Folia 流程同时覆盖跨区域客户端。上述数据不代表长期生态平衡或真人验收。

P6 四人工厂由两组双人组织组成。玩家邀请在线伙伴并接受邀请后，双人 roster 原子保存到 `plugins/StarGenesis/factory/organizations.json`。两组各有成员在线后，由四人中的任意一位创建比赛：

```text
/genesis factory invite <player>
/genesis factory accept
/genesis factory start 42
/genesis factory bid 15
/genesis factory step 4
/genesis factory order press_a 2
/genesis factory step 2
/genesis factory dashboard
/genesis factory replay
```

拍卖在第4 tick 截止，模拟 ore 归最高出价组织，平局按组织 ID 决胜。获胜方提交订单时 escrow 预留 credits、ore 与 parts。订单按 deadline 完成；固定 seed 可能触发部分生产故障，补偿路径退回已处理和未处理 ore、credits 与 parts，并把每条 saga 事件写入 replay。比赛 replay 原子保存在 `<matchId>.factory.json`，服务端重启后可由 UUID 查回并继续。比赛账户只保存模拟资产，不调用 Bukkit 库存、真实经济或 StarCore。P6 代码测试和服务端启动不代替四名真人玩家的比赛验收。

P7 ArenaRuleset 使用现有 schema-v1 严格格式。玩家编辑有界宽高、时长、单位预算、版本和访问许可，并可撤销最近32次修改。公开提交需要 publicRead，所有依赖都必须在内置设计或公开 catalog 中存在，版本/hash 精确一致且授权 publicRead + allowClone；依赖循环和超过16层的链拒绝提交。`publish` 将固定设计 hash 提交到持久审核队列，批准前不进入公开 catalog。具有 `stargenesis.arena.review` 权限的审核员必须是非作者，拒绝决定需记录理由；批准后才将该版本不可变发布，升级需提高语义版本。公开 catalog 仅包含内置示例与已批准 UGC：

```text
/genesis arena create player:compact
/genesis arena set width 24
/genesis arena set height 20
/genesis arena set units 6
/genesis arena access public on
/genesis arena access clone on
/genesis arena preview
/genesis arena save
/genesis arena publish
```

规则提交后暂不出现在公开目录。具备 `stargenesis.arena.review` 权限且不是规则作者的审核员使用自己的账号处理：

```text
/genesis arena review pending
/genesis arena review show player:compact 1.0.0
/genesis arena review approve player:compact 1.0.0
/genesis arena catalog
```

`/genesis arena review pending` 查看待审核条目，作者使用 `status <id> <version>` 查询自己的状态和拒绝理由，审核员使用 `show <id> <version>` 查看完整记录。审核员使用 `approve <id> <version>` 发布，或使用 `reject <id> <version> <reason>` 记录拒绝原因。`clone <publishedId> <newId>` 仅在源规则允许克隆时创建个人草稿，`dep add <id> <version> <sha256>` 固定许可依赖。规则只描述尺寸、时长、单位预算与依赖，不携带脚本、命令或任意执行字段。P7 已有编辑、许可、审核、发布与 catalog 工作流，竞技运行时和反作弊比赛验收仍未实现。

P8 已把公开 ArenaRuleset 接入双队领地比赛。两队各邀请至少两位、最多四位玩家，已接受的 team roster 原子保存在 `plugins/StarGenesis/arena-matches/teams.json`。全部 roster 同时在线后按已发布规则创建比赛。每一逻辑回合必须由所有参赛玩家各提交一次 `pass` 或 `claim`，服务端收齐后同时解析冲突、消耗单位并按领地数量补给：

```text
/genesis arena match invite <player> cedar
/genesis arena match accept
/genesis arena match start genesis:arena_ruleset 1.0.0 42
/genesis arena match claim 1 0 2
/genesis arena match pass
/genesis arena match dashboard
/genesis arena match replay
/genesis arena match watch <matchId>
```

合法 claim 必须贴邻本队领地，不能越过 ArenaRuleset 的地图尺寸或单位预算。争夺按 seed 和 round 轮换先手确定胜负，时限结束后按 territory score 结算。比赛文件保存 ruleset 精确 hash、完整 roster、事件和每轮 commitments；每次提交先验证坐标、邻接、比赛状态和本队所有已提交动作的合计消耗，合法动作才原子保存，四人动作齐全后才更新 match replay。非法提交保留原存档，玩家可重新提交合法动作。断线玩家重连后恢复 match 和待提交动作，observer 只读 dashboard/replay，不能提交动作。Paper/Folia 五客户端实服流程已注入 socket 断线，验证 observer 所见 pending action、同一 UUID 重连、原 match 恢复和最终回合 replay；这是自动化客户端覆盖，不替代真人双队验收和公平性研究。P8 不生成真实物品或货币。

P9 settlement saga 已定义显式授权 permit、credits/item 双腿、稳定幂等 operation key 查询、补偿退款和不确定结果的人工复核状态。Paper 按 saga UUID 原子保存状态并以 revision 防覆盖冲突。当前 StarCore 公开转账没有外部幂等键，TransactionFabric 不执行钱包/物品副作用，所以没有注册真实结算 adapter，生产扣款与物品交付继续关闭。

P10 signed design import 使用 Ed25519 envelope。管理员先把 publisher 的 X.509 PEM 公钥暂存到 `publisher-inbox`，再用 OP 命令显式加入信任列表；签名包放入固定 inbox，玩家导入到自己的私有目录：

```text
plugins/StarGenesis/publisher-inbox/acme-2026.pub
plugins/StarGenesis/design-inbox/starter_pack.json
/genesis design publishers add publisher.acme-2026 acme-2026
/genesis design publishers list
/genesis design install starter_pack
/genesis design list
/genesis design inspect genesis:example
```

导入会校验 keyId 信任、签名、design SHA-256 和 schema version，不自动执行或迁移内容。没有管理员配置的受信公钥时不会接受任何包。签名不自动授予 Arena 发布、资产或世界修改权限。

publisher 可用发布包 CLI 读取严格校验过的 design JSON 并生成签名 envelope。密钥轮换时，先加入新 keyId 并验证新签名包，再用 `/genesis design publishers revoke <keyId> <reason>` 撤销旧 key。撤销会持久记录原因和时间、拒绝该 keyId 再次注册，并使之后的导入和验证拒绝旧 key 签名的包。信任状态由 `stargenesis.publishers` 权限保护，默认仅 OP 可管理。

签名包放入 `design-inbox/<package>.json`：

```sh
java -cp stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar scripts/SignedDesignPackageSigner.java keygen <publisher-private.pem> <publisher-public.pem>
java -cp stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar scripts/SignedDesignPackageSigner.java sign <publisher-private.pem> <keyId> <design.json> <signed-package.json>
java -cp stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar scripts/SignedDesignPackageSigner.java verify <publisher-public.pem> <keyId> <signed-package.json>
```

publisher 私钥必须由发行方在仓库外保管。CI 和 P11 自测只创建临时密钥并在测试后删除，不能代替运营方配置真实 trust root、第三方 bridge 或发行方验收。

Folia 的 P1–P10 命令工作流已由五个自动客户端在实际 Folia 服务端验证。玩家先移动到不同 region，再完成 radio、工厂、Arena 和签名设计流程。P1 在 Folia 使用命令查看状态、停止、回放和恢复检查点，不打开玩家背包。另有四个并发 P1 沙盒、每场四个模拟造物的15分钟 Folia 负载证据；这是受控模拟工作负载，不替代真人验收、生产容量测试或长期负载研究。

## 真实服务器测试

提供独立目录，避免接触生产世界；脚本绑定 localhost，生成最小测试世界，执行命令并停服，输出原始控制台日志、机器可读结果和工件哈希。

```sh
python scripts/server_smoke.py --server-jar /path/paper.jar --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --directory .work/server-paper --accept-eula
python scripts/server_smoke.py --server-jar /path/folia.jar --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --directory .work/server-folia --kind folia --accept-eula
```

`--accept-eula` 仅在接受 Mojang EULA 时使用；也可预先提供已接受的 eula.txt。可用 `--java /path/java` 指定运行时。测试只证明真实服务器控制台生命周期，不证明真人客户端、模型、跨区域操作或第三方经济兼容。

## 工程结构与后续开发

五个有实现模块：api（稳定契约）、design（格式/安全校验/迁移/蓝图物理评估和撤销工作区）、sim（时钟/守恒账本/生态/replay）、integrations（能力发现合同）、paper（插件宿主和玩家工作区/replay 存储）。JSON Schema 和哈希示例在 design 模块资源目录；SHA-256 为完整性校验，不是作者签名。

P3 typed IR/解释器已有 Paper 编辑、持久化、隔离调试和资源沙盒执行入口。该执行仍不操作 Minecraft 真实世界或背包。每次变更执行正式 verify，新增公共接口须有合同测试。阶段门、缺口和实际证据持续记入 docs；后续实现不得把模拟数量兑付真实物品，不得用 Mock 冒充实机验收。
