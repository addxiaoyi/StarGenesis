# StarCore 源码审计（P0）

审计日期：2026-11-19。源码仓库：`https://github.com/addxiaoyi/starcore`。固定提交：`f769e62799e769a18fe51305b545869a17f80cd1`，提交时间 `2026-11-18T23:34:47Z`。本次通过机器已有 Git 认证只读浅克隆并读取源码；未读取或输出凭据。匿名 HTTP API/网页返回 404，但认证 Git 可访问，不能据此宣称仓库不存在。

以下文件路径均相对于上述固定提交，行号供复查。这里只证明源码存在与调用关系；未构建 StarCore、未安装到服务器、未运行其测试，不能据此声称集成已经运行。

## 基线与入口

| 证据 | 结果 |
| --- | --- |
| `pom.xml:6–9,30–33` | Maven `dev.starcore:starcore:1.0.0`；Java 21；`paper-api:1.21.11-R0.1-SNAPSHOT`（provided） |
| `src/main/resources/plugin.yml:1–4` | 插件名称 `StarCore`；主类 `dev.starcore.starcore.StarCorePlugin`；api-version `1.21` |
| `src/main/java/dev/starcore/starcore/api/StarCoreApi.java:25–30` | 受支持入口有 `version()`、`modules()`、`<T> Optional<T> service(Class<T>)` |
| `src/main/java/dev/starcore/starcore/StarCorePlugin.java:456,510,1630–1641` | 启动注册 `StarCoreApi` 到 Bukkit ServicesManager，关闭注销 |
| `src/main/java/dev/starcore/starcore/api/StarCoreApiProvider.java:27–34` | `service` 查内部注册表；未启用服务返回 Optional.empty，不能把插件存在等同于能力存在 |
| `docs/API_COMPATIBILITY.md:3–11`；`docs/api/starcore-api-v1.txt` | ABI 门禁只递归跟踪 API 入口显式引用类型。通用 `service(Class)` 能找到某类，不自动使任意内部类型成为稳定 ABI |

StarGenesis 适配器未来应通过服务管理器取得入口，再逐项取得所需服务；不直接构造实现、不访问数据库、不反射猜名称。当前 P0 能力注册表只报告安装/版本，所有真实集成能力保持 inactive。

## 国家、成员、领土与权限

`src/main/java/dev/starcore/starcore/module/nation/NationService.java` 的真实公开契约包括 `nationOf(UUID)`、`nationById(NationId)`、`nationByName(String)`、`claimAt(String world,int chunkX,int chunkZ)`、`claimsOf(NationId)`、`isFounder(UUID,NationId)`、成员与等级查询。`StarCoreApi.nationService()` 为 typed convenience getter。`NationModule.java:381,417,485,878–945,1005` 存在实际状态写入实现与同步保护；`551–552` 将领土查询交给基础领土服务。

注意存在两套领土概念：国家区块申领是 `foundation.territory.model.TerritoryClaim/ChunkCoordinate`；另有注册到 service registry 的 `dev.starcore.starcore.territory.TerritoryService`（`StarCorePlugin.java:1056–1063`），管理可重叠体积区域、`Location` 查询和所有者。后者不能替代前者。国家成员身份或 claimAt 的结果也不等同于获准操作任意方块/库存；部署前还必须明确动作权限、土地/战争约束及第三方保护。

`NationService.atWar` 默认实现返回 false（`NationService.java:139–154` 附近）；实际 `NationModule.java:1065–1090` 才查询 WarService。适配器不可将接口默认值当作权威战争状态。`Nation` 是可变对象；只把必要字段复制成 StarGenesis 不可变快照，不把实现对象交给模拟工作线程。

## 科技

`src/main/java/dev/starcore/starcore/module/technology/TechnologyService.java:9–40` 已有 `availableTechnologies()`、`unlockedTechnologies(NationId)`、`hasTechnology(NationId,String)`、`costOf(String)`、`unlock/revoke`、`getTotalModifier`。`TechnologyModule.java:74–114,151–153,197–242` 存在服务注册/注销与查询、持久化写入实现。

StarGenesis 仅计划读取科技作为生存部署许可证/组件类别门槛，不调用 unlock、不修改 StarCore 科技，不把国家加成引入统一预算竞技。具体科技 stable key 需读取运行服配置并逐项验证，本次没有虚构对应科技。

## 经济与事务

| 层次 | 已实读源码及语义 | 集成限制 |
| --- | --- | --- |
| 玩家经济服务 | `foundation/economy/EconomyService.java:10–61`：BigDecimal balance/has/deposit/withdraw/setBalance/transfer；`StarCorePlugin.java:1114–1123` 注册选择后的服务 | API 无外部幂等键/evidence ID；不得直接重试扣款或把模拟数量 deposit 成真钱 |
| 国库 | `module/treasury/TreasuryService.java:14–91`：balance、deposit/tryDeposit、withdraw、transfer；transfer 有收款失败后补偿语义 | 不是背包与钱包的原子事务，方法也无外部结算幂等键 |
| 经济门面 | `foundation/economy/EconomyFacade.java:45–69,99–151`：委派玩家余额/国库服务并可选绑定 | 是实现便利门面；不是受支持的专门跨插件原子交易 API |
| 事务账本协调 | `foundation/economy/transaction/TransactionFabric.java:34–42,91–182`：持久化 begin/transition/findByIdempotencyKey；同键不同参数被拒绝（`218–230`） | `StarCorePlugin.java:948–955` 真实注册，但 begin/transition 仅记录事务状态，不自行执行经济扣款或真实货物交付；不能声称其提供 exactly-once 外部副作用 |
| 扩展点 | `foundation/economy/extension/EconomyTransactionExtensionPoint.java:15–43` 和 `StarCorePlugin.java:773–774` | 已实现且注册的交易审批/金额聚合器，不是可授权领取或物品原子交付接口 |

P9 需要受测试的经济 adapter、明确来源/收款方、持久化 saga/outbox、外部结算唯一键、每阶段证据和人工复核分支。`TransactionFabric` 可作为后续桥接提案的现成基础，但其内部类型不在 typed API ABI 递归表内，需上游明确稳定公开契约并做故障注入测试。不能访问 StarCore 数据库绕过服务。

## 事件、已实现扩展点与文档提案

- `core/event/StarCoreEventBus.java:61–84,153–210` 有真实 subscribe（返回注销 Runnable）、同步/异步发布和调度。模块从 `StarCoreContext.eventBus()` 使用它；`StarCoreApi` 没有 typed eventBus getter。不要猜测 `StarCoreApi.getEventBus()`。
- `module/event/EventService.java:7–18` 提供国家事件记录与查询；`EventModule.java:67,107` 真实注册服务并订阅核心总线。这是事件历史服务，不能当作取消世界操作的授权事件接口。
- `foundation/events/DomainEventBus.java` 另有不同的简单总线，不能和核心 StarCoreEventBus 混用。
- `module/nation/extension/NationClaimExtensionPoint.java:14–41` 是已实现的领地**价格**计算扩展，不是通用造物领地授权接口。
- `docs/EXTENSION_POINTS_REPORT.md` 明确包含“扩展点建议”。例如 `WarDeclarationExtensionPoint`/`WarConclusionExtensionPoint` 在该报告有设计片段，但本次对 `src/main/java` 精确类名搜索无匹配；不得注册或调用这些虚构类。每个提案都必须重新核对实际源码，不能因部分扩展点后来落地就将整份报告视作已实现 API。

## 线程与兼容风险

源码包含 `core/scheduler/FoliaCompatScheduler.java` 的区域/实体/全局调度分支（`62–74,131–172`），但其 `isMainThread()` 在 Folia 下直接返回 true（`194–201`），这不是当前区域拥有某个实体/方块的证明。`plugin.yml` 本次未找到 `folia-supported` 声明；不能宣称 StarCore 已通过 Folia 或把这个辅助方法作为跨区域访问依据。

在没有实机线程契约测试前，StarGenesis 不从模拟线程直接调用 Bukkit 或 StarCore 实现，不启用经济/领土写入。后续 adapter 必须在目标服允许的线程取快照，写入按所属区域或全局权威服务封送，跨区先准备/验证、再异步证据结算；存在 synchronized 仅说明锁保护，不证明 Bukkit 调用安全。

## 下一步可复现验证

1. 对固定 SHA 构建 StarCore，并记录依赖工件哈希及其自身测试结果（本次未执行）。
2. 在 Java 21 + Paper 1.21.11 实服安装，验证服务注册/注销、可选模块关闭后的 Optional.empty、nation/claim/technology 只读查询。
3. 为 service 生命周期、空服务、默认 atWar、权限拒绝编写 adapter contract test；保留可信实现与模拟实现的证据区别。
4. 与上游确认专门的外部 idempotent transfer/settlement API 与事件订阅稳定面；再测试崩服、重复键、收款失败、补偿失败、恢复及真实库存守恒。
5. 单独取得目标 Folia build，进行跨区域真实检查；未通过则 capability 明确拒绝，不能靠标记 folia-supported 绕过。

本审计完成的是源码证据与边界识别；实际 StarCore 功能 adapter、Paper 集成与 Folia 验收均未完成。

## 2026-11-26 结算接口复查

GitHub分支列表确认main仍为上述`f769e62799e769a18fe51305b545869a17f80cd1`。另只读核对开发分支`codex/plugin-all-stages-20261125`的固定提交`7850efac96ce0721979c370d0ca8d4f4765c0935`，不将它当作main。两者`StarCoreApi.java`的Git blob均为`9715acefc7ff6dfffbac7f66a1ec3b080f5b6284`，`foundation/economy/EconomyService.java`均为`85e3b8fb17ab5fa204f63a38e30b01dfd39020f1`，玩家存取款/转账仍没有外部幂等键。

该开发提交的`module/resource/TradeSettlementCoordinator.java`明确只恢复持久收据，不执行扣款、退款或资产交付；`TradeSettlementIntent`也声明准备记录不是资产成功证明。新增`NationResourceTransactionService`具有国家资源原子转移、authorityDomainId和receipt查询合同，但其资产域是国家资源库，不能映射为玩家货币或真实背包物品。默认transfer仍可返回UNAVAILABLE，默认receipt为空；接入前必须检查具体native provider、持久权威、在途和故障语义。

本次通过GitHub固定提交和本机其他checkout的Git对象读取，未修改其工作树，未运行StarCore或启用StarGenesis真实结算。五份原始源码及Git blob/SHA-256见`../evidence/p9/provider-capability/starcore-audit/summary.json`。它更新接口证据，不构成P9 provider验收通过。
