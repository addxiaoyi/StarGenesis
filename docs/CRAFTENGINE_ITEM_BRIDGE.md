# CraftEngine 物品桥接

`StarGenesisCraftEngine` 是独立的可选扩展，允许 Paper 1.21.11 使用 CraftEngine 26.9.1 的已加载物品目录、原生工厂和完整物品快照。基础插件没有 CraftEngine 类型依赖。默认reactor仍可独立构建；扩展没有Folia支持声明。

## 使用与构建

```text
python scripts/prepare_craftengine_api.py
./mvnw -B -ntp -Pcraftengine-bridge verify
```

Windows 使用 `mvnw.cmd`。同时构建Helstera扩展时先准备其固定API，再使用 `-Phelstera-bridge,craftengine-bridge`。安装基础JAR、`stargenesis-craftengine-4.0.0-SNAPSHOT.jar` 和单独取得的已验证CraftEngine提供方。

`stargenesis.items` 默认OP，可执行：

```text
/genesis item status
/genesis item inspect namespace:item
/genesis item held
```

`held` 仅捕获发送者自己的主手物品，输出ID、数量、材质、快照大小和原始字节哈希。查询不写背包。没有玩家grant或交付命令；`ItemStackBridge.create` 返回脱离背包的原生物品副本，调用方必须另行验证任何库存操作授权。

## 完整状态与生命周期

`ItemCatalog` 提供复制的不可变定义；ID必须完整且不超过128字符，标签最多128项。`ItemStackBridge` 提供 `create`、`capture`、`restore`。每个调用都在Paper主线程重新检查实际提供方与加载状态，并持有CraftEngine的真实 `ResourceOperationCoordinator` lease，排除其异步重载和资源工作流。返回前重新检查提供方身份。重入、停用、替换或未完成启动均拒绝，不跨调用缓存Bukkit物品或定义。基础插件/提供方停用会注销服务，旧引用也拒绝访问。

快照保留Paper `serializeAsBytes()` 的原始GZIP NBT字节；持久方可以原样保存。读取时先检查256 KiB压缩、1 MiB展开、4096节点、32层深度及各数组/列表长度，再进入Paper原生解码器，防止其无限堆预算解析恶意长度。数量限1–64并遵守物品实际 `max_stack_size` 组件。材质、数量和DataVersion必须匹配；自定义物品必须仍有当前定义，删除定义不能将其悄悄降为原版物品。

恢复后重新序列化并比较完整的有类型NBT。compound键顺序本身没有语义，Paper实测会改变组件键排列，因此比较忽略这种排列和GZIP表示；字段缺失/增加、tag类型、标量位模式、数组/列表顺序、空列表元素类型、根名称和全部嵌套PDC仍须完全相等。解码忽略未知字段也会被发现并拒绝。原始payload仍保留原字节及其SHA-256；哈希相同不是来源、所有权或交付授权。

仅接受当前Paper的DataVersion，拒绝隐式数据迁移。模板更新后恢复旧物品使用旧快照完整状态，不从新模板重建。API创建、快照与恢复不会自动插入/消耗背包、生成掉落实体或执行结算；生产交付必须遵守独立P9幂等与授权合同。

## 提供方来源

`scripts/craftengine-api-lock.json` 固定官方Maven仓库的core/bukkit JAR、source JAR和POM六份完整SHA-256。准备脚本逐字节校验后安装带工件哈希的独立Maven坐标，仅修改POM版本，保留原GPL许可元数据。API以provided方式编译，不替换上游类型，不打包到扩展内。能力审计的sourceRevision为该锁文件完整 `sha256:`，并非虚构的Git提交。

实际服务器提供方来自本机已存在的隔离验收工件，SHA-256为 `021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f`。它与官方API JAR并非全部类字节相同：core有7个类不同（包括CraftEngine根类），bukkit有3个事件处理类不同；缺少的525个命名空间内类均位于内嵌adventure/option库。实际资源协调器及其Lease/BusyException、CraftEngineItems、BukkitCraftEngine和组件物品工厂与锁定API完全相同；差异列表及全部hash见本批 `provider-provenance.json`。

上述类比对不证明整个提供方等同于官方构建；结论限定于记录的工件与实际运行测试。版本字符串也不是工件认证。替代JAR、其他服务器版本和Folia第三方兼容需重新验证。未修改或分发相邻SenLuo项目的数据和运行时JAR。

## 验证与边界

单元测试覆盖加载/线程/平台/版本拒绝、真实资源协调器互斥、异常释放、重入、生命周期变化、ID/元数据边界、GZIP/NBT预算、恶意长度/深度/节点、类型和顺序差异，以及从真实Paper保存的固定二进制样本。实服检查使用实际工厂、当前定义和原生编码，包含中文lore、嵌套PDC、模板替换、删除定义、两次独立进程恢复及停用后的旧引用拒绝。

两名固定离线客户端另验证普通玩家权限拒绝、OP手持查询、缺失ID和无grant命令；测试插件只在隔离服准备物品，持续核对双方全部槽位及完整NBT。它不构成生产交付、资源包视觉或真人体验验收。

故意停用提供方时，CraftEngine自身输出固定的“Please do not disable plugins at runtime.”错误级提示。测试仅在明确起止标记之间识别其完整七行格式，保留原日志；其他错误仍使验证失败。这是故障隔离检查，不建议运行服热停用插件。

主仓库Java21/25各通过616项测试（82类），失败/错误/跳过均为0；21项Python、6项Node及两套JDK共八项跨进程锁/临时签名探针通过。最终Java21工件的Paper两次启动各46项检查、两客户端命令、Helstera投影，以及无扩展Folia的22项基础控制台检查均通过。

证据归档于 `evidence/p10/item-bridge/summary.json`。本实现属于RC4之后的SNAPSHOT；完整第三方物品/配方、资源包显示、实际交付、运营trust-root及正式发行验收仍有待完成。

复现时为每次运行准备新的目录及既有已接受的eula.txt，再调用 `scripts/server_craftengine_item_smoke.py`：指定JDK、实际Paper JAR、提供方/base/bridge JAR、Paper libraries目录、CraftEngine libs/config来源和隔离目录。默认顺序启动两次；命令客户端模式额外使用 `--clients --runs 1 --node <node> --node-modules <modules>`。配置仅复制CraftEngine库和配置，关闭测试资源包工作流并创建单个自定义物品；不复制生产world、玩家库存或数据库。
