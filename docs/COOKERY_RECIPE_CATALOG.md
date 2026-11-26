# 森罗料理配方目录

`stargenesis-cookery` 是可选 Paper 1.21.11 插件，通过 `RecipeCatalog` 服务返回元数据。编译使用 `addxiaoyi/SenLuo_Plugin` 的 `7aca215fca067314d1593dd287621f96fb8913e4` 真实源码，料理版本为 1.3.0，CraftEngine 为 26.9.1。基础插件和纯 Java 合同不引用料理或 CraftEngine 类，可单独运行。

## 构建与安装

先执行 `python scripts/prepare_craftengine_api.py` 和 `python scripts/prepare_cookery_api.py`，再执行 `./mvnw -Pcraftengine-bridge,cookery-bridge verify`；Windows 使用 `mvnw.cmd`。也可与 `helstera-bridge` 一起构建。料理 API 准备脚本校验 558 个原始 Git blob，在新目录编译未修改的上游 main/compat 源码，并运行 28 项实际上游配方测试；不生成 API stub，不解析或编译 Spigot NMS。依赖及其 SHA-256 写入 `dependencies.json`。此 API JAR 仅作 provided 编译依赖，不打包到 StarGenesis。

安装 `StarGenesis`、`CraftEngine`、`KaleidoscopeCookeryPlugin` 和 `StarGenesisCookery`。物品桥接插件并非料理元数据桥接的依赖。料理桥接未声明 Folia 支持，启动时仅接纳 Paper 1.21.11。版本匹配与固定编译源码不等于运行时 JAR 的密码学认证；实服证据记录所用本地上游构建的完整 SHA-256 及关键类字节对比。

## 查询

`stargenesis.recipes` 默认仅 OP 拥有。控制台可执行：

```text
/genesis recipe status
/genesis recipe list MILLSTONE ACCURATE 1
/genesis recipe inspect MILLSTONE ACCURATE namespace:recipe
```

列表每页 16 个 ID，最多 128 页。查询键包含器具、配方类别和完整小写命名空间 ID。同 ID 可以出现在不同类别或器具；同一键出现多个注册项时拒绝歧义查询，不选取其中一项。列表排序只影响浏览顺序，不代表上游匹配优先级。菜单展示项不进入该目录。

器具包括 `POT`、`STEAMER`、`STOCKPOT`、`SHAWARMA`、`MILLSTONE`、`CHOPPING_BOARD`、`TEAPOT`、`BAMBOO_TRAY`。状态命令列出各器具数量。查询命令最多输出 16 个字段，每个字段值截取到 240 字符；完整有界字段通过 Java 服务读取。

| 类别 | 保留字段 |
|---|---|
| ACCURATE | input、results(key/weight，保留顺序与重复项)、cook、rotations、resultCount、lore、cookingTime |
| FLEX | result、cook、perfect、liquids、nullable carrier、useEquivalentFoods、useSeasonings、norm、totalWeight、cookingTime、stirFryCount |
| POT | mode、ingredients(每个槽位的可替代物品集合)、result、resultCount、stirFryCount、sourceTime、nullable carrier |
| STOCKPOT | mode、ingredients、soupBase、result、resultCount、time、nullable carrier、visuals(cookingModel/finishedModel/cookingColor/finishedColor) |
| CHOPPING | input、stage、values、mode、results、extras；每个结果均保留 key/count/weight |
| TEAPOT | fluid、input、ingredientCount、result、resultCount、time |
| BAMBOO_TRAY | input(含 `#tag` 前缀)、result、resultCount、mode、durationTicks |

保留原始处理值，包括表示继承器具默认设置的 0。权重不归一化，不抽样；砧板权重的相对权重/独立百分比语义仍由其 mode 决定。ingredient 槽位顺序保留，槽位内部集合按 ID 排序。标签及等效食物引用不展开，不把目录查询视为输入匹配或“当前一定能烹饪”的证明。元数据不包含器具运行状态或玩家授权。

## 生命周期与快照边界

每次请求要求 Paper 主线程、两个上游插件启用且版本匹配、CraftEngine fully loaded 且不在启用/重载/停止状态、料理注册表不在配置加载期。请求持有实际 `ResourceOperationCoordinator` lease，并在 `FoodRecipeRegistry.readSnapshot` 内完成计数或复制。独立公开 `atomicUpdate` 不受该 lease 约束，因此读前后还比较 generation，变化时拒绝返回；返回前再次校验当前 provider、资源 provider、注册表身份和桥接关闭状态。回调中的替换、关闭与重入同样被拒绝。异常或链接错误会释放 lease。

上游 `readSnapshot` 会沿用调用方已有的 reader/writer 上下文；调用方已固定旧视图或处于未提交写事务时，查询可能读取该上下文。其 `generation()` 返回全局最新计数，不能证明继承视图的版本。本接口不暴露 generation/freshness token，也不声称返回值是最新已提交视图。正常顶层命令没有继承上下文，每次重新读取注册表；副本可留作历史元数据，不能用于授权、下单、恢复制作或判断现时配方仍存在。

料理或 CraftEngine 停用会停用桥接并注销服务，已保存的 bridge 引用也拒绝继续查询。基础插件停用会关闭借用的服务。上游注册表的 `close()` 本身仍允许读取且无公开 closed getter；桥接以真实插件生命周期为准，不承诺识别第三方绕过插件生命周期单独调用 registry.close() 的行为。

## 预算

注册表超过 2,048 项时拒绝查询，并在调用上游列表复制方法前检查数量。单条定义最多 2,048 个值节点、8 层、每个集合/映射 128 项、每段字符串 1,024 UTF-16 code units、总文本 32,768 code units。ID 最长 128 字符。仅接受字符串、Integer、有限 Double、Boolean、null、字符串键映射和列表；所有嵌套容器深拷贝并冻结。循环引用、非法类型和超限不会发布半份定义。

桥接只读取公开元数据访问器，不调用 plan、roll、buildOutputs、buildResult 或真实物品工厂。它只激活 `cookery.read`，不激活料理物品解析、生产制作、背包交付或经济结算。

## 验证

单元测试使用真实上游 record、`FoodRecipeRegistry` 和 CE coordinator，覆盖七类字段、权重重复项、快照隔离、删除/替换、歧义 ID、配置容量、并发发布、异步 lease、线程与平台、版本/停用、回调替换/关闭和链接失败。命令测试覆盖权限先于 provider 访问、非法键、分页、输出预算和缺失服务。API 准备脚本回归覆盖原始换行字节、路径/符号链接拒绝和未列入锁的源码隔离。

`scripts/server_cookery_recipe_smoke.py` 使用实际 Paper 和料理运行时执行两次顺序启动，探针代码为 `CookeryRecipeServerProbe.java`。需提供已有 `eula=true` 的新隔离目录、实际服务端/插件 JAR、Paper libraries、CraftEngine libs/config/internal状态映射以及实际料理资源包目录。Windows路径超过260字符时，脚本使用扩展文件路径枚举与复制，防止遗漏资源。真实配方配置重载、删除、恢复、停用和两次进程读取均有独立检查；命令使用合成 sender。具体成功/失败、工件、源码和日志哈希以本批证据为准，不将单元测试、合成 sender 或控制台检查称为真人体验、实机制作或资源包外观验收。

本批Java21/25各634项主项目测试及28项实际料理API测试通过。实际Paper两次启动分别40/42项检查通过，481条配置配方全部可复制且跨进程内容一致；另加五类API测试数据与两个配置测试配方共同覆盖七类。所有重载、删除、停用检查及首次失败记录详见 `evidence/p10/recipe-catalog/summary.json`。
