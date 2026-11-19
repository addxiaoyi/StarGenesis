# SenLuo / CraftEngine 源码审计

审计日期：2026-11-19。源码仓库 `addxiaoyi/SenLuo_Plugin`；GitHub main 和独立浅克隆均解析到 **`6a432fe54097a3ebf22ba5757e016b602c711f28`**。本轮只读源码，没有执行其构建或 Paper/Folia 联调。下文所有路径和行号均对应此提交，不能把仓库已有验收文档当作 StarGenesis 的实机验收。

## 1. 取证和工作树隔离

通过 GitHub REST `GET /repos/addxiaoyi/SenLuo_Plugin/commits/main` 确认 SHA，再执行 `git clone --depth 1 --branch main https://github.com/addxiaoyi/SenLuo_Plugin.git .work/audit/SenLuo_Plugin` 并核对 `git rev-parse HEAD`。旁边的用户目录 `../SenLuo_Plugin` 位于 `codex/full-mod-parity`、HEAD `7157bc5e739f501946f1fc062a4310ed16881948`，有大量未提交修改；本轮未修改或用于代表远端 main。

源码证据永久定位形式为 `https://github.com/addxiaoyi/SenLuo_Plugin/blob/6a432fe54097a3ebf22ba5757e016b602c711f28/<下列路径>#L<行号>`。

## 2. 构建、插件身份与版本

| 项目 | 源码确认 | 证据 |
|---|---|---|
| CraftEngine | `26.9.1`；core/bukkit compileOnly，不应由 StarGenesis 打包 | `gradle.properties:4`、`series/gradle.properties:4`、`build.gradle:88–92`、`series/build.gradle:24–26` |
| Paper API | `1.21.11-R0.1-SNAPSHOT` | `build.gradle:88`、`series/build.gradle:24` |
| Java | 主代码 Java 21；根工程另有 26.x NMS 源集使用 Java 25 编译器 | `build.gradle:7–15,151–170`、`series/build.gradle:18–21` |
| Cookery | Bukkit 插件名 `KaleidoscopeCookeryPlugin`；api-version `1.21` | `src/main/resources/plugin.yml:1–5` |
| Doll | `KaleidoscopeDollPlugin` | `series/doll/src/main/resources/plugin.yml:1–7` |
| Tavern | `KaleidoscopeTavernPlugin` | `series/tavern/src/main/resources/plugin.yml:1–7` |
| Deco | `KaleidoscopeDecoPlugin` | `series/deco/src/main/resources/plugin.yml:1–7` |
| MusicPlus 内容 | 插件名实际是 `KaleidoscopeMusicPlugin`，不能按 `MusicPlus` 插件名探测 | `series/music/src/main/resources/plugin.yml:1–6` |

五个 plugin.yml 均 `depend: [CraftEngine]`、`folia-supported: true`。声明支持不证明实际行为已通过 Folia。根工程与 `series/` 是两个 Gradle 工程（`settings.gradle`、`series/settings.gradle:9–10`），不是 Maven 五模块。

## 3. 可依赖的公开入口与内部实现

**明确公开入口**：`net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI`，类注释明确要求通过此类集成，避免依赖 controller/behavior（`src/main/java/net/kaleidoscope/cookery/api/KaleidoscopeCookeryAPI.java:12–18`）。已确认方法：`plugin()`、`choppingBoardKnives()`、`millstoneAnimals()`、`itemTags()`、`blockTags()`、`entityProperties()`、`recipeMenuStyle()`、`recipeMenuHooks()`、`potCookConditions()`、`foodRecipes()`、`applianceFoods()`、`soupBases()`（同文件 `28–146`）。它们是内容/配方/条件注册表与 UI 扩展点，不是物品转账、国家或经济事务 API。

`foodRecipes()` 返回实际 `FoodRecipeRegistry`：有 `readSnapshot`、`generation`、`atomicUpdate`、`planAccurate/planFlex/planStockpot/planPot`、注册和查询等方法（`recipe/FoodRecipeRegistry.java:178–191,415–509`）。API 存在不代表自动生产可任意绕过原始配方消耗；StarGenesis 后续应只读配方快照并由可信适配器执行真实物品结算。

**公开事件**：`PotExtractDishEvent` 和 `StockpotExtractDishEvent` 是可取消的 PlayerEvent，含 location/dish 和 setDish（各文件 `15–59`）；`SteamerBreakFullEvent` 含产品列表（`api/event/SteamerBreakFullEvent.java:17–54`）；`MillstoneGrindCompleteEvent` 是可取消 Event，`player()` 可能为 null，产品列表可变，取消后保留原料并阻止自动重试（`api/event/MillstoneGrindCompleteEvent.java:12–55`）。应在事件调用线程内复制必要的 ID/数量/PDC 摘要再传入模拟，不把 Bukkit 对象送往工作线程。

**不得作为稳定外部 SDK**：`series/common/.../Items`、`InventoryTrade`、`SavedItems`、控制器及 `SeriesScheduler` 是实现细节。特别是发布时 `net.kaleidoscope.series` 被 relocate 到 `net.kaleidoscope.<module>.internal`（`series/build.gradle:60–70`），不能仅因源代码 `public` 就直接链接原包名。未发现 Doll/Tavern/Deco/Music 具有与 Cookery 同级的对外入口或稳定服务事务 API。

## 4. 物品稳定 ID 与真实库存边界

| 内容 | 源码中的真实标识例子 | 证据 |
|---|---|---|
| Cookery 导出物品 | `kaleidoscopecookery:<name>`；来源模型命名空间 `kaleidoscope_cookery` **不同** | `tools/export_cookery_catalog.py:24,327`；`tools/export_cookery_food_pack.py:72–86` |
| Doll | `kaleidoscope_doll:doll_0` 等 | `series/doll/src/main/resources/catalog.json:3–8` |
| Tavern | `kaleidoscope_tavern:empty_bottle`、`:brandy`、`:grape_juice` | `series/tavern/src/main/resources/catalog.json:4–19` |
| Deco | `kaleidoscope_deco:apple_platter` 等 | `series/deco/src/main/resources/catalog.json:21–22` |
| Music | `musicplus:handheld_instrument` | `series/music/src/main/java/net/kaleidoscope/music/MusicPlugin.java:33` |

资源包容器的 `packNamespace`（`kseries_doll/tavern/deco/music`）与实际物品命名空间分开定义，**不可**将容器名拼成物品 ID（`series/tools/module-specs.json:4–35`）。这些是源码/导出目录证据，仍须在目标服务器 CE 加载完成后逐个检查是否真实注册，不能把 catalog 行当作物品已可用。

源码实际使用 CE `CraftEngineItems.getCustomItemId(stack)` 识别真实自定义 ID，普通物品退回 Material key；构建经过 `BukkitItemManager.instance().getBuildableItem(Key.of(id))`，缺失抛错（`series/common/.../Items.java:18–43`）。StarGenesis 后续桥接应针对官方 CE API 编译并契约测试，不能调用已被 relocate 的 SenLuo 内部类。

物品完整状态不能只保存 ID+数量：`SavedItems.put/get` 使用 `ItemStack.serializeAsBytes/deserializeBytes`，校验数量（`series/common/.../SavedItems.java:9–25`）；`ItemState` 将带版本/来源信封写入 PDC、限制 262144 字节并对状态物品设单堆叠（`ItemState.java:10–37`）。真实物流必须保留 PDC/组件和精确栈状态，不得按 ID 重造替代品。CE 重载监听器会 `Items.clearCache()`（`SeriesResourceReloadListener.java:7–12`）；适配器也须重验 ID 并失效派生缓存。

`InventoryUtils.consumeItem` 对创造模式返回成功但不扣物品（`src/main/java/net/kaleidoscope/cookery/util/InventoryUtils.java:58–70`），不是可直接用于守恒结算的通用扣款 API。`InventoryUtils.give` 可能掉落满包余量（`:41–50`）；真实订单不能据返回调用就宣称完成。`BottleController` 确实通过 `InventoryTrade.exchange` 将背包交换与状态提交配对，但该控制器为 package-private、内部交换不是跨插件幂等事务（`series/tavern/.../BottleController.java:14,38–65`）。

全文检查 `src/main/java` 与 `series` 的 Java/YAML，未找到 StarCore/Vault/economy 结算接口。没有本次证据支持将 SenLuo 宣称为经济权威或直接兑现模拟资源。

## 5. 线程、生命周期与安全降级

`SeriesScheduler.player` 先检查 `Bukkit.isOwnedByCurrentRegion(player)`，否则由 CE `scheduler().platform().run(..., player)` 投递；延迟工作检查插件仍启用、玩家在线（`series/common/.../SeriesScheduler.java:16–35`）。`RecipeMenuProvider` 注释明确 UI 回调在拥有玩家的线程执行（`src/main/java/net/kaleidoscope/cookery/api/ui/RecipeMenuProvider.java:10–17`）。此模式不能解读为可同步访问其他玩家/其他区块。

`SeriesPlugin.onLoad` 注册 CE before-enable task；onEnable 注册交互和重载监听器；onDisable 通过 player scheduler 关闭自己菜单（`SeriesPlugin.java:16–47`）。`mayUse` 要求同世界、距离平方不超过 64、AntiGriefLib INTERACT 通过（`:53–57`），不能替代 StarCore 国家/领土授权。强制移除家具时会保存恢复快照但明确不自动发放物品（`:63–86`）。适配器关闭须停接新操作、等待或持久化既有订单、保留未决凭证，不可复制该快照为自动补偿物资。

## 6. P0 能力结论与后续契约测试

P0 可报告“插件已安装/版本已知/源码入口已审计”；**真实物品读写、家具操控、经济、生产均尚未激活且未在 StarGenesis 中实机验证**。缺失 CE 或对应插件时只禁用该能力，核心实验仍使用模拟资源；未知版本必须待测，不能无条件启用。

后续上线前至少执行：

1. CE 26.9.1 + Paper 1.21.11 冷启动；逐项读取真实 ID，缺失/伪造 ID 拒绝，重载后重新验证；记录全部 JAR 与资源包哈希。
2. Cookery API 配方查询、事件取消/空 player 分支、插件关闭再启用；不把单元测试冒充事件实际触发。
3. 双玩家真实库存/PDC运输，满包、卸载、重启、重复确认和强制移除恢复；物品合计只移动一次；创造模式不允许结算生产成本。
4. Folia 分区玩家/家具操作通过各自 owner 调度；跨区异步确认、超时和停服恢复，不做跨区同步背包访问。
5. 国家/货币仍经 StarCore 后续已验证适配器结算，SenLuo 不承担该权威。

以上测试在本轮 **未执行**。源码附带 `docs/VALIDATION.md` 和 `series/docs/ACCEPTANCE.md` 的历史验证只用于设计复现步骤，不能扩大为本工程兼容性通过。
