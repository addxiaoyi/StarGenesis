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
python scripts/build_release_candidate.py --version 4.0.0-rc.3 --java java
```

候选 JAR、SBOM 与构建日志写入 `.work/releases/<version>/`，来源树哈希、实际构建参数、JDK版本、测试汇总和日志hash写入 `docs/evidence/p11/release-candidate-<version>.json`。已有输出或证据会拒绝覆盖；重复制备同版本时指定不同的 `--output-directory` 和 `--evidence-file`。内存受限时可用 `--fork-count 0` 在Maven进程内执行全部测试，默认仍为1个测试JVM；设置合适的 `MAVEN_OPTS` 并保留到证据。测试失败、跳过或没有报告均不能产出成功候选。可将候选 JAR 传给 `scripts/server_smoke.py`，分别在隔离 Paper 与 Folia 目录验证启动和退出。RC 构建不会修改工作树版本，也不会生成运营方发行签名。

Java 21/25 CI同时运行模块边界、Java完整测试、候选构建脚本的Python回归、跨进程结算锁和工作目录锁探针，以及发行/设计包签名自检。CI归档插件、SBOM、Surefire报告和工具结果；本地可执行 `python -m unittest discover -s scripts -p test_release_candidate_builder.py` 检查候选证据拒绝边界。

实验室、生态、工厂和竞技场比赛在关闭时停止准入并排空已接受命令；超时会取消尚未开始的命令并中断当前操作。若工作线程仍未退出，其目录锁继续阻止新实例启动，日志明确报告关闭未完成；不会仅因发出中断就报告干净退出。原存档格式不变，重启读取最后成功提交的状态。关闭预算、恢复边界和本地探针命令见[工作线程生命周期](docs/WORKER_LIFECYCLE.md)。

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

实验启动时先保存第0步，每100步以及停止/结束时将进度和回放一起保存。保存失败会显示 `SAVING`，暂停推进并自动重试；上次有效存档继续保留。`resume` 会重演历史并核对完整状态，`replay` 只读取和验证，不覆盖记录。重启后可以恢复已保存的进度；异常退出可能回退到最近检查点。新实验使用独立场次 ID，成功保存后替代该玩家的上一局；旧版分开的快照和回放仍可读取。

P2 蓝图工作区提供个人虚拟结构编辑、结构代价预览、撤销和显式保存。常用流程如下：

```text
/genesis blueprint create player:rover
/genesis blueprint add solar genesis:solar_panel 1 0 0 core
/genesis blueprint add engine genesis:thruster -1 0 0 core
/genesis blueprint preview
/genesis blueprint undo
/genesis blueprint save
```

支持 `genesis:core`、`frame`、`solar_panel`、`sensor`、`wheel`、`thruster` 和 `wing` 模块。预览报告质量/承载、材料代价、供能、推力、升力、能力和组件坐标。蓝图以作者 UUID 分目录、以 ID 哈希命名并通过 schema/hash 校验，每位作者最多保存64份。默认私有；作者可授权协作者编辑，或开放只读查看和许可克隆：

```text
/genesis blueprint access editor <collaborator UUID>
/genesis blueprint access public on
/genesis blueprint access clone on
/genesis blueprint version 1.1.0
/genesis blueprint save
/genesis blueprint list <author UUID>
/genesis blueprint open player:rover <author UUID>
/genesis blueprint clone <author UUID> player:rover player:my_rover
```

作者使用 `access revoke <UUID>` 撤销编辑许可，修改许可/版本均需 `save` 才生效，支持 `undo`。协作者只能修改结构，不能修改许可、版本或来源；每次保存检查磁盘上当前授权和打开时的基准哈希，撤销后的旧会话不能写回，并发编辑只能有一个基准版本保存成功。其他作者目录须显式提供 UUID，公开只读不授予编辑或克隆权。克隆要求已有读取许可且源作者允许克隆（原作者可复制自己的设计），副本默认私有，记录原作者、设计 ID、精确版本与内容哈希，且不会覆盖同名副本。所有修改只作用于虚拟设计，不放置方块或物品。单元测试覆盖授权、撤销、冲突、重启与克隆来源；新增协作命令尚待实际客户端验收。

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

调试器与沙盒执行均取程序声明和服务器 `nodes-per-agent-step` 中较低的指令上限，分步/继续执行不会重置已用额度；沙盒同时使用配置中的时长与消息预算。编辑、撤销或更改虚拟输入后重新执行，旧断点状态不会继续应用到新程序。保存失败保留未保存草稿、撤销历史和原基准哈希，可修复存储后重试；冲突保存不能覆盖其他已提交版本。程序列表核对作者、内容哈希和ID派生文件名。每次短时模拟使用独立场次ID并核对该场回放，退出后不保留模拟世界或信号；已保存程序可以重开。

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

`chip` 主动生成 PDC 绑定当前协议 ID/hash 的纸质知识芯片，`join` 检查玩家背包持有对应芯片。消息下一逻辑 tick 送达。`plugins/StarGenesis/radio-state.json` 以 schema v1 保存消息幂等证据、未确认投递、发送/确认事件、Contract 状态与协议/定义哈希，文件上限16 MiB。排队、确认和Contract收件先在候选状态中执行，原子保存成功后才生效；保存失败保留原内存与文件，可用相同sequence或原消息重试，失败动作不会在关服时被补写。Contract超时立即保存；普通逻辑时钟每20步检查点保存，失败时停在前一逻辑tick并重试，关服保存可重试。

启动时校验版本、协议哈希、消息/确认证据和契约事件重演结果；不一致的状态拒绝加载。当前tick已使用的消息预算也会恢复，重启不能额外获得发送额度。玩家退出会注销在线端点，重新join后可领取仍未确认的消息，服务端重启后也需重新join，离线期间逻辑tick暂停。无线只使用虚拟坐标和玩家UUID，消息投递不读写Minecraft世界、聊天或真实库存。Paper/Folia既有五客户端流程验证重复poll、ack清空、掉线后原envelope重投及幂等重发；新增保存失败恢复逻辑已通过单元回归，尚未重复实服流程，不替代真人长时通信验收。

P5 封闭生态舱使用有限 matter 账本。生产者、食草者和分解者的每个生长、进食、繁殖与死亡都在 soil、detritus 和个体 biomass 账户间转移同一种物质。玩家可调整物种的 intake、繁殖门槛、寿命和突变率，参数在服务器硬上限内生效：

```text
/genesis ecology start 42
/genesis ecology program producer 4 6 48 150
/genesis ecology step 20
/genesis ecology dashboard
/genesis ecology replay
```

`program` 会在当前逻辑步应用新规则。`step` 每次最多推进20步。繁殖会转移已有 biomass，突变只修改有界物种参数，并受32个个体、8代和512次出生限制。默认实验仍为500步；使用 `/genesis ecology start <seed> <ticks>` 可在开始时指定1–1536步，例如 `start 52008 1536`。时长是上限，种群可能提前灭绝，不能延长已有实验或修改历史。账本为指定时长预留最多98,304次转移证据，保持原有100,000硬上限。`dashboard` 显示当前实验的真实时长预算、物质余额、种群、代数和出生/死亡。个人 replay 保存在 `plugins/StarGenesis/ecology/<player UUID>.ecology.json`，重载会从 seed 重演输入并核对初始状态、每步状态哈希和事件哈希。`reset` 删除该玩家的生态舱。整个流程只改模拟账本，不访问 Minecraft 世界、背包或经济服务。

新建生态舱使用v2生命周期：体内物质达到繁殖阈值后停止摄入，寿命一半时成熟，成功繁殖后至少间隔寿命四分之一（均向上取整）。降低储量阈值不会删除已有物质，编辑程序和重启也不会清除繁殖间隔。所有死亡先释放空位，再处理本步繁殖；seed决定竞争次序、进食目标和突变，玩家UUID不再改变相同实验的种群轨迹。面板显示这些参数。新默认程序为producer`(6,4,320,150)`、grazer`(4,24,512,100)`、decomposer`(3,4,320,80)`；老生态舱保留v1原规则与回放哈希。具体合同见[生态引擎v2](docs/ECOLOGY_ENGINE_V2.md)。

此前v1版本的Paper/Folia 自动客户端另运行四个 extinction 对照 profile 和固定 seed 52008 candidate，均核对 total matter 与 replay。四个对照 profile 在 SNAPSHOT/RC 的 Paper/Folia 实服运行中均在500步前灭绝；candidate 在四次服务器/版本复现中均达到500步，终态 population 为 producer/grazer/decomposer 26/2/4。纯 Java 引擎另在两个归档 Paper/Folia Alice worldId 上扫描 seed 52008–52107，200/200 均到500 ticks并保留三物种；这是模拟扫描，不是200次服务器运行。2026-11-25 完成 Paper/Folia 各八 seed（52008–52015）自动客户端实服扩展，16/16 均到500 tick、保留三物种、matter=1000，replay 全部通过。Folia 流程同时覆盖跨区域客户端。上述数据不代表v2实服验证、长期生态平衡或真人验收。

`scripts/EcologyLongRunStudy.java` 默认在v1引擎的1,536步预算下比较六组固定参数，记录500/512/1024/1536步种群、出生/死亡、首次物种灭绝时点，并逐步检查守恒、最终验证 replay。运行时按生产者、食草者、分解者顺序在第0/1/2步应用参数，其后不干预：

```sh
java -cp stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar scripts/EcologyLongRunStudy.java .work/ecology-long-study.json 4 2
```

末尾两个数字分别为 seed 数（1–32）和 world ID 数（1–8），默认4和2；可追加逗号分隔的profile名称或`all`（默认），然后依次指定首seed、首world编号和`v1|v2`。原三组长期对照见 `docs/evidence/p5/long-run/summary.json`，旧候选在第512步失去食草者。新默认配置和最高突变率研究使用：

```sh
java -cp stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar scripts/EcologyLifecycleStudy.java .work/ecology-v2-validation.json 32 default,mutation-max 20261126
```

可复现的参数搜索先固定 world/seed 筛选，再验证其他世界与种子：

```sh
java -cp stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar scripts/EcologyBalanceSearch.java .work/ecology-balance-search.json 128
java -cp stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar scripts/EcologyLongRunStudy.java .work/ecology-long-cycle-validation.json 16 4 long-cycle-candidate
```

128组搜索只有候选63达到1536步并保留三物种，其原8组验证全过；扩展到4个world ID、16个seed后仅40/64组保留三物种，24组发生物种灭绝。全部64组每步守恒、最终回放通过。候选参数 producer `(6,4,320,0)`、grazer `(4,24,512,0)`、decomposer `(3,4,320,0)` 仅用于研究，未设为默认。原始样本及恢复回归见 `docs/evidence/p5/balance-search/summary.json`。有限样本存活不代表长期平衡，可选时长与新候选仍缺实服和真人验收。

P6 四人工厂由两组双人组织组成。玩家邀请在线伙伴并接受邀请后，双人 roster 原子保存到 `plugins/StarGenesis/factory/organizations.json`。两组四名成员均在线且未参加其他活跃工厂时，任意一位可创建比赛；同一组玩家可以在上一场结束后再次开赛：

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
/genesis factory matches
/genesis factory select <matchId>
/genesis factory leave
```

拍卖在第4 tick 截止，模拟 ore 归最高出价组织，平局按组织 ID 决胜。获胜方提交订单时 escrow 预留 credits、ore 与 parts。订单按 deadline 完成；固定 seed 可能触发部分生产故障，补偿路径退回已处理和未处理 ore、credits 与 parts，并把每条 saga 事件写入 replay。比赛 replay 原子保存在 `<matchId>.factory.json`，新场次使用独立 run UUID，旧 v1 回放继续保持原哈希。重连/重启优先恢复活跃参赛，`select` 可查看自己的历史；查看历史不能绕过活跃参赛限制。比赛结束后任一成员 `leave` 解散双人组织，历史不会重新加入已退出成员。

目录限256场且合计64 MiB，单场限8 MiB；写入失败保留原数据。具备 `stargenesis.factory.manage` 的管理员（默认 OP）可用 `/genesis factory prune <matchId>` 清理已结束且全部订单已完成或补偿的历史。旧 `reset [matchId]` 改为同权限的历史清理别名，普通成员不能删除共享比赛，管理员也不能删除活跃比赛。`matches` 对普通玩家只列本人记录，对管理员列全部记录。比赛账户只保存模拟资产，不调用 Bukkit 库存、真实经济或 StarCore。P6 代码测试和服务端启动不代替四名真人玩家的比赛验收。

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

`/genesis arena review pending` 查看待审核条目，作者使用 `status <id> <version>` 查询自己的状态和拒绝理由，审核员使用 `show <id> <version>` 查看完整记录。审核员使用 `approve <id> <version>` 发布，或使用 `reject <id> <version> <reason>` 记录拒绝原因。`clone <publishedId> <newId>` 仅在源规则允许克隆时创建个人草稿，`dep add <id> <version> <sha256>` 固定许可依赖。草稿保存以磁盘上当前编辑许可为准；许可、版本和来源/依赖变更仅限原作者，提交的新文档不能自授编辑权。规则只描述尺寸、时长、单位预算与依赖，不携带脚本、命令或任意执行字段。P7 已有编辑、许可、审核、发布与 catalog 工作流，P8 提供竞技运行时，真人创作与反作弊比赛验收仍未完成。

克隆来源固定原作者、ID、版本和hash，副本默认私有；原有依赖按原pin保留。已提交或发布的规则ID绑定原作者，其他作者须使用新ID，内置 `genesis:arena_ruleset` 不可覆盖。审核记录必须与文档作者、ID、版本和hash同时一致。保存批准决定后若发布文件写入失败，原审核员可在重启后重试同一approve命令。草稿最多64位作者、每人32份，发布256份、审核512份；新增作者在创建目录前检查预算。共享依赖图按每个pin验证一次，并检查最长路径不超过16层。

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
/genesis arena match matches
/genesis arena match select <matchId>
/genesis arena match unwatch
/genesis arena match withdraw
/genesis arena match leave
```

合法 claim 必须贴邻本队领地，不能越过 ArenaRuleset 的地图尺寸或单位预算。争夺按 seed 和 round 轮换先手确定胜负，时限结束后按 territory score 结算。比赛文件保存 ruleset 精确 hash、完整 roster、事件和每轮 commitments；每次提交先验证坐标、邻接、比赛状态和本队所有已提交动作的合计消耗，合法动作才原子保存，四人动作齐全后才更新 match replay。非法提交保留原存档，玩家可重新提交合法动作。断线玩家重连后恢复 match 和待提交动作，observer 只读 dashboard/replay，不能提交动作。Paper/Folia 五客户端实服流程已注入 socket 断线，验证 observer 所见 pending action、同一 UUID 重连、原 match 恢复和最终回合 replay；这是自动化客户端覆盖，不替代真人双队验收和公平性研究。P8 不生成真实物品或货币。

同一队伍、规则和 seed 可在比赛结束后再次开赛，每场保留独立记录。重连/重启默认优先恢复未结束的参赛比赛，其次是正在观战的比赛，最后才是已结束的历史；相同优先级按 match ID 稳定选择，其他记录可通过 `matches` 和 `select` 查看。`watch` 会选中目标比赛，`unwatch` 释放观战席位；动作只作用于当前选择的参赛比赛。手动选择仅保留到断线，观战名单会持久保存。`withdraw` 只撤回本人尚未结算的动作，可用于修复旧存档中错误封存的输入；已结算回合不可撤回。`leave` 仅在本人没有活跃比赛时退出赛前 roster，历史比赛不会把已退出成员重新加回。

比赛目录最多128场、合计64 MiB，单场8 MiB、每场128位 observer。新比赛会先检查整个时长内的回放容量上界，并为其他活跃比赛保留完成所需空间；默认1200轮可用，过长规则在开赛前拒绝并提示此 roster 可用的轮数。可缩短规则时长、等待其他比赛结束，或由管理员清理已完成历史后重试。写入仍检查实际大小，超限保留原记录。管理员可用 `/genesis arena match prune <matchId>` 永久删除已结束且无待处理动作的历史比赛以释放空间；该命令需要 `stargenesis.arena.manage`（默认 OP），不能删除进行中的比赛。v1 回放保留原引擎和 commitments；新场次使用 v2，详见 `docs/MIGRATIONS.md`。旧版本已经开始的超长比赛仍兼容读取，但不获得新的容量保证。上述生命周期新增命令目前通过纯 Java 回归验证，尚未重复实服客户端验收。

P9 settlement saga 已定义显式授权 permit、credits/item 双腿、稳定幂等 operation key 查询、补偿退款和不确定结果的人工复核状态。Paper 在同一 saga 的排他锁内完成查询、授权、外部调用及状态保存，防止并发过期恢复与尚未返回的付款/交付冲突；revision 继续防覆盖。新写入固定原请求和 permit，拒绝跳过交易步骤或重开终局；同请求的重复 begin 在过期后仍可返回已存状态，但不能创建新交易。真实文件写盘失败后仍先查询外部幂等结果，再恢复或退款。接入要求和本地跨进程锁验证命令见 `docs/SETTLEMENT_PROVIDER_CONTRACT.md`。当前 StarCore 公开转账没有外部幂等键，TransactionFabric 不执行钱包/物品副作用，所以没有注册真实结算 adapter，生产扣款与物品交付继续关闭。

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

信任库的读写使用目录锁，多个store实例不能并发重绑同一keyId或突破32个活跃公钥上限。新key同时占用未来撤销容量，活跃加已撤销身份最多4096个；重复添加相同公钥不增加占用。撤销记录先持久保存再清理公钥，清理未完成时撤销也已生效，重试保留原原因和时间。旧库若已满4096条撤销记录，新的撤销会在写盘前拒绝并保留当前状态；需要保留历史语义的容量迁移，不能直接删除撤销记录释放空间。新的容量/并发限制尚无实服验收。

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

`scripts/server_lifecycle_smoke.py`使用四个固定客户端、三次服务端启动验证生态v2的768→1536步续跑、工厂待完成订单补偿、Arena待结算动作恢复和实验室运行中关服保存。每次启动前后逐字节核对存档，每次退出检查后台目录锁释放。环境准备、运行命令和验证边界见[重启续跑测试](docs/SERVER_LIFECYCLE_TEST.md)。

## 工程结构与后续开发

五个有实现模块：api（稳定契约）、design（格式/安全校验/迁移/蓝图物理评估和撤销工作区）、sim（时钟/守恒账本/生态/replay）、integrations（能力发现合同）、paper（插件宿主和玩家工作区/replay 存储）。JSON Schema 和哈希示例在 design 模块资源目录；SHA-256 为完整性校验，不是作者签名。

P3 typed IR/解释器已有 Paper 编辑、持久化、隔离调试和资源沙盒执行入口。该执行仍不操作 Minecraft 真实世界或背包。每次变更执行正式 verify，新增公共接口须有合同测试。阶段门、缺口和实际证据持续记入 docs；后续实现不得把模拟数量兑付真实物品，不得用 Mock 冒充实机验收。
