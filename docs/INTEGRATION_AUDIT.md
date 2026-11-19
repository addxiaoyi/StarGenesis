# 三方集成差异审计

审计日期：2026-11-19。以固定提交源码为准，不将公开类存在、插件安装、上游测试文档或 Mock 适配器等同于 StarGenesis 实机兼容。完整文件/行号与语义证据见三份分项审计。

| 仓库 | 本次实际读取提交 | 详细证据 |
|---|---|---|
| addxiaoyi/starcore | f769e62799e769a18fe51305b545869a17f80cd1 | [audits/STARCORE.md](audits/STARCORE.md) |
| addxiaoyi/helsteraMobs | bb6d662c17c713691e40278f5fd7d7db0155512c | [audits/HELSTERA.md](audits/HELSTERA.md) |
| addxiaoyi/SenLuo_Plugin | 6a432fe54097a3ebf22ba5757e016b602c711f28 | [audits/SENLUO.md](audits/SENLUO.md) |

StarCore 经机器现有 Git 认证只读取得；匿名 HTTP 404 不代表不存在。SenLuo 使用独立 main 克隆，没有改动旁边用户已有其他分支和未提交修改。

## StarCore

真实入口 `dev.starcore.starcore.api.StarCoreApi`，由 `StarCorePlugin.java:1630–1641` 注册到 Bukkit ServicesManager；`service(Class)` 返回 Optional，各模块可能未启用。国家/领土/科技以服务快照查询，不能把成员身份或 claimAt 直接当作库存操作许可。

`foundation/economy/EconomyService.java:10–61` 的真实余额/转账方法无外部幂等参数；`foundation/economy/transaction/TransactionFabric.java:91–182` 有持久 begin/transition/findByIdempotencyKey，但只协调记录，不原子执行钱包与背包副作用。P9 必须实现持久 saga/outbox 与补偿证据，不盲目重试扣款。

事件总线、事件历史服务和两套领土服务语义不同。`StarCoreApi` 没有 typed eventBus getter；文档提到的 WarDeclarationExtensionPoint/WarConclusionExtensionPoint 在本次源代码未实现。内部 service 可取得不等于稳定 ABI，不能直接依赖数据库或推测方法。

## helsteraMobs

`helstera-api/.../HelsteraApi.java:14–32` 已有 get/isAvailable；`Helstera.java:18–43` 暴露模型/实例/行为/事件等服务。API ready 不等于指定模型已加载。`InstanceManager.java:10–40` 要求全部方法主线程，spawn/bind/despawn 实际访问 Bukkit/render；`BehaviorRegistry` 注册允许任意线程，但动作/条件执行主线程，且缺通用注销能力。

EventBus 同步在派发线程调用监听器并吞 listener Throwable；不得以事件名推断线程。模型生成不自动等于完整 AI 生物生成；引擎实例 int ID 不等于稳定 AgentId。

当前 Maven 基线 Paper1.21.1，plugin.yml 无 Folia 支持声明；实际运行与AI使用 Bukkit scheduler。目标1.21.11需构建、spawn/动画/despawn/资源包与客户端验证。Folia 必须另行解决引擎线程约束；不能仅由 StarGenesis 调度器包装就宣称支持。

## SenLuo / CraftEngine

真实五插件名为 KaleidoscopeCookeryPlugin、KaleidoscopeDollPlugin、KaleidoscopeTavernPlugin、KaleidoscopeDecoPlugin、KaleidoscopeMusicPlugin，依赖 CraftEngine26.9.1。Cookery 公开入口 `net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI` 提供配方/条件/内容注册表；其余 series/common 实现被 shade/relocate，源码 public 不代表外部 SDK。

Cookery 导出 ID 使用 `kaleidoscopecookery:*`，不能与源模型 `kaleidoscope_cookery` 混淆；Doll/Tavern/Deco/Music 有各自命名空间。目录中的 ID 仍需目标服 CraftEngine 实际注册后逐项验证。物品识别依赖 CE API，库存转移必须保留 PDC/组件/完整栈，重载后失效缓存。

未发现 StarCore/Vault 经济结算接口。创造模式不扣物品的 consumeItem、可能掉落满包余量的 give 以及内部 InventoryTrade 都不能当作幂等跨插件交付接口。

## P0 能力策略和下一步

CapabilityRegistry 只读启动时安装清单，独立呈现 installed/enabled、versionCompatible、apiVerified、paperVerified 和逐项能力。当前没有真实第三方 adapter，全部功能 inactive；预期缺失依赖返回 NOT_INSTALLED，已安装但无验证为 ADAPTER_NOT_VERIFIED，不反射猜方法。

后续顺序：固定依赖构建与哈希→目标服加载/关闭→服务生命周期→只读契约→模型/库存/权限实际操作→故障注入与恢复→独立 Folia 证据。Paper 证据不能代替 Folia flavor/version 的验证。当前第三方联调均未执行。
