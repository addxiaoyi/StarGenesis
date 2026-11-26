# 三方集成差异审计

审计日期：2026-11-19。以固定提交源码为准，不将公开类存在、插件安装、上游测试文档或 Mock 适配器等同于 StarGenesis 实机兼容。完整文件/行号与语义证据见三份分项审计。

| 仓库 | 本次实际读取提交 | 详细证据 |
|---|---|---|
| addxiaoyi/starcore | f769e62799e769a18fe51305b545869a17f80cd1 | [audits/STARCORE.md](audits/STARCORE.md) |
| addxiaoyi/helsteraMobs | 9298292538dc9d7c5ee800ff7e03f4c10e34ba97 + 锁定清理补丁（2026-11-26） | [audits/HELSTERA.md](audits/HELSTERA.md)、[只读桥接](HELSTERA_MODEL_CATALOG.md) |
| addxiaoyi/SenLuo_Plugin | 6a432fe54097a3ebf22ba5757e016b602c711f28 | [audits/SENLUO.md](audits/SENLUO.md) |

StarCore 经机器现有 Git 认证只读取得；匿名 HTTP 404 不代表不存在。SenLuo 使用独立 main 克隆，没有改动旁边用户已有其他分支和未提交修改。

## StarCore

真实入口 `dev.starcore.starcore.api.StarCoreApi`，由 `StarCorePlugin.java:1630–1641` 注册到 Bukkit ServicesManager；`service(Class)` 返回 Optional，各模块可能未启用。国家/领土/科技以服务快照查询，不能把成员身份或 claimAt 直接当作库存操作许可。

`foundation/economy/EconomyService.java:10–61` 的真实余额/转账方法无外部幂等参数；`foundation/economy/transaction/TransactionFabric.java:91–182` 有持久 begin/transition/findByIdempotencyKey，但只协调记录，不原子执行钱包与背包副作用。P9 必须实现持久 saga/outbox 与补偿证据，不盲目重试扣款。

事件总线、事件历史服务和两套领土服务语义不同。`StarCoreApi` 没有 typed eventBus getter；文档提到的 WarDeclarationExtensionPoint/WarConclusionExtensionPoint 在本次源代码未实现。内部 service 可取得不等于稳定 ABI，不能直接依赖数据库或推测方法。

## helsteraMobs

已增加Paper限定的被动实验室投影，要求实际提供方包含固定清理补丁，见[当前投影合同](HELSTERA_LAB_PROJECTION.md)。模型目录与实体投影分别验证；下面旧API行号/风险描述保留其审计语境，不能据此宣称资源包外观、动画或Folia已验收。

`helstera-api/.../HelsteraApi.java:14–32` 已有 get/isAvailable；`Helstera.java:18–43` 暴露模型/实例/行为/事件等服务。API ready 不等于指定模型已加载。`InstanceManager.java:10–40` 要求全部方法主线程，spawn/bind/despawn 实际访问 Bukkit/render；`BehaviorRegistry` 注册允许任意线程，但动作/条件执行主线程，且缺通用注销能力。

EventBus 同步在派发线程调用监听器并吞 listener Throwable；不得以事件名推断线程。模型生成不自动等于完整 AI 生物生成；引擎实例 int ID 不等于稳定 AgentId。

当前 Maven 基线 Paper1.21.1，plugin.yml 无 Folia 支持声明；实际运行与AI使用 Bukkit scheduler。目标1.21.11需构建、spawn/动画/despawn/资源包与客户端验证。Folia 必须另行解决引擎线程约束；不能仅由 StarGenesis 调度器包装就宣称支持。

## SenLuo / CraftEngine

真实五插件名为 KaleidoscopeCookeryPlugin、KaleidoscopeDollPlugin、KaleidoscopeTavernPlugin、KaleidoscopeDecoPlugin、KaleidoscopeMusicPlugin，依赖 CraftEngine26.9.1。Cookery 公开入口 `net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI` 提供配方/条件/内容注册表；其余 series/common 实现被 shade/relocate，源码 public 不代表外部 SDK。

Cookery 导出 ID 使用 `kaleidoscopecookery:*`，不能与源模型 `kaleidoscope_cookery` 混淆；Doll/Tavern/Deco/Music 有各自命名空间。目录中的 ID 仍需目标服 CraftEngine 实际注册后逐项验证。物品识别依赖 CE API，库存转移必须保留 PDC/组件/完整栈，重载后失效缓存。

2026-11-26增加独立CraftEngine物品桥接，官方Maven API/source/POM以完整SHA-256锁定，来源字段改为该锁文件哈希。Paper实服验证当前目录、原生脱离背包创建、完整有界NBT快照、两次进程恢复、删除/替换定义和资源操作互斥；两名客户端验证权限和查询不改变任何库存槽位。`items.resolve`与`items.create`只在当前提供方就绪时激活，后者表示脱离背包创建。实际提供方工件与官方API有已披露类差异，结论限于证据中的哈希；见 `CRAFTENGINE_ITEM_BRIDGE.md`。SenLuo系列的完整物品/配方、视觉和生产交付仍须分别验收。

未发现 StarCore/Vault 经济结算接口。创造模式不扣物品的 consumeItem、可能掉落满包余量的 give 以及内部 InventoryTrade 都不能当作幂等跨插件交付接口。

## P0 能力策略和下一步

CapabilityRegistry 独立呈现 installed/enabled、versionCompatible、apiVerified、paperVerified 和逐项能力。启动保留诊断，debug命令重新检查当前提供方。可选Helstera扩展验证`model.inspect`与`entity.project`；投影只对支持固定清理契约的提供方激活，清理未完成时降级为查询。可选CraftEngine扩展检查实际资源锁与加载状态后验证`items.resolve`与脱离背包的`items.create`。其他真实能力保持inactive；缺失依赖返回NOT_INSTALLED，已安装但未配置验证扩展为ADAPTER_NOT_VERIFIED，不反射猜方法。

2026-11-26首次执行真实Helstera只读目录桥接：固定源码、实际API编译、Paper1.21.11-132模型解析/注册/卸载/重载、提供方停用与旧引用拒绝通过。该历史批次不包含显示实体、资源包、动画或Folia；上游实例创建失败未回滚已用实际运行时复现。后续投影批次以固定依赖补丁修复创建与清理契约，证据见`docs/evidence/p10/lab-projection/summary.json`。StarCore真实结算和SenLuo物品桥接仍未验收。
