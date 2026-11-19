# helsteraMobs 源码审计（P0）

审计时间：2026-11-19。读取公共 GitHub API 的 `commits/main`、递归 tree 和该提交下 raw 源码；没有依赖旧文档推断接口。

- 仓库：`addxiaoyi/helsteraMobs`
- 审计提交：`bb6d662c17c713691e40278f5fd7d7db0155512c`
- 本地临时证据：`.work/audit/helsteraMobs/COMMIT.txt`、`tree.json` 和按原路径下载的源码（不纳入发布物）。
- 判定：公开 API 和运行时实现确实存在；本轮尚未将其装入 StarGenesis 的目标 Paper 1.21.11，也未执行模型/资源包/客户端验收。

## 已核验接口与实现证据

下列路径均相对于上述提交，行号对应源码。可用 GitHub `blob/<commit>/<path>#L<line>` 定位。

| 能力 | 公开契约与具体证据 | 实际限制 |
| --- | --- | --- |
| 根 API | `helstera-api/src/main/java/dev/helstera/api/HelsteraApi.java:14-32`：`get()`、`isAvailable()`、内部 `register/unregister`；未启动时 `get()` 抛 IllegalStateException。`Helstera.java:18-43` 暴露模型、实例、行为、事件、可见性、资源包、集成和迁移服务，默认 `apiVersion()` 为 `1.0.0` | 插件 enabled 不能等同于 API ready；须同时检查版本、isAvailable、具体服务及模型是否加载。不得调用内部 register/unregister 接管引擎。 |
| API 生命周期 | `helstera-plugin/src/main/java/dev/helstera/plugin/HelsteraPlugin.java:803-809` 在初始化后注册根 API，再开始自动模型加载；`:954-1007` 注销 API、停调度器、AI、清理实例和 renderer | API ready 不代表指定模型已异步加载成功。桥接必须处理 disable/unload，不持有失效服务。 |
| 实例生成/绑定 | `helstera-api/.../instance/InstanceManager.java:10-40`：`spawn(String, Location, SpawnOptions)`、`bind(String, Entity, SpawnOptions)`、查询、despawn、despawnAll、despawnWorld、effectiveHitbox，文档明确 **全部方法要求主线程**。`helstera-runtime/.../instance/InstanceManagerImpl.java:51-79` 实际查模型、建实例、创建显示实体并更新变换 | `spawn` 仅实例和显示；实现 `:59-60` 明确不发 ModelSpawnEvent，不能把它当作完整生物 AI 生成 API。未载入模型抛异常。实例 ID 是进程内递增 int（`:39,84`），不能作为 StarGenesis 稳定 AgentId。 |
| 销毁与事件 | `InstanceManagerImpl.java:127-165` 删除实例并派发 ModelRemoveEvent，`:177-189` 清理无效载体 | 模型生命周期不得成为确定性模拟真相；重建显示由 StarGenesis AgentId 到临时实例 ID 的映射负责。 |
| 实例投影与动画 | `helstera-api/.../instance/ModelInstance.java:20-74` 提供 instanceId/model/baseEntity/location/teleport/scale/rotation/animation/attachItem/despawn/isValid；`.../animation/AnimationController.java:15-34` 提供 play/stop/pause/resume/reset/hasAnimation | Bukkit Entity/Location/ItemStack 只能留在桥接层；引擎动画状态不能回写模拟资源和位置。 |
| 模型注册 | `helstera-api/.../model/ModelRegistry.java:9-39`：query 任意线程；register/unload 主线程，load 异步解析后主线程注册；validate 返回异步错误列表 | 需核验模型 ID、骨骼/动画、客户端资源包；count/get 存在不等于显示成功。 |
| 行为扩展 | `helstera-api/.../behavior/BehaviorRegistry.java:7-61`：registerCondition/registerAction/registerState，注册任意线程，条件/动作在主线程决策节拍；`helstera-ai/.../BehaviorRegistryImpl.java:29-48` 以 trim+lowercase 键替换注册值，`:72-100` 处理条件和动作异常 | 没有公开 unregisterCondition/unregisterAction/unregisterState 或注册 token，不能承诺扩展可动态卸载。不得把玩家图编译为任意 Consumer、Java 或引擎命令。条件组合默认方法可 UnsupportedOperationException，具体实现才支持传播异常的组合求值。 |
| 事件总线 | `helstera-api/.../event/HelsteraEventBus.java:10-40`：register/unregister/post；监听器在 post 所在线程同步调用；按精确 event.getClass 匹配，Throwable 被隔离 | 事件可能来自主线程或异步线程，监听器自行封送；不是 Bukkit Event、不是持久队列、没有恰好一次或事务保证。StarGenesis 必须生成自身 evidence ID/outbox，不依赖该总线结算。 |
| 渲染实现 | `helstera-render-paper/.../display/DisplayRenderer.java:34,98-161` 使用隐藏 ArmorStand、每骨骼 ItemDisplay、Interaction 和 TextDisplay | 一个逻辑造物可产生多个实体，物化预算必须计算总显示实体；服务器无异常不证明客户端资源包外观正常。 |

## 版本与线程兼容风险

- 根 `pom.xml:9,28-32`：自身版本 1.0.0、Java source/target 21、Paper API **1.21.1-R0.1-SNAPSHOT**。
- `helstera-plugin/src/main/resources/plugin.yml:1-4`：插件名 `helsteraMobs`、version 1.0.0、入口 `dev.helstera.plugin.HelsteraPlugin`、api-version `1.20`，没有 `folia-supported` 声明。
- `helstera-runtime/.../scheduler/HelsteraScheduler.java:71` 和 `helstera-ai/.../AiManager.java:477` 实际使用 `Bukkit.getScheduler().runTaskTimer`；插件 `HelsteraPlugin.java:785,1097` 也将事件/加载完成回调放到传统主线程调度器。**不能据此宣称 Folia 支持。** 外部桥接换成区域调度器也不能修复依赖内部的全局主线程模型。
- 上游 `tools/paper-integration/version-matrix.json` 只把 Paper **1.21.1-133** 列为 strict/releaseValidated；1.21.4 和 **1.21.11 均为 exploratory、releaseValidated=false**。这是上游源码中记录的验收声明，不是 StarGenesis 本轮执行的结果。
- 主要风险为 Display/Interaction/物品模型与资源包格式的跨版本变化、弃用 Bukkit API、实体载入销毁和跨区域访问。公开方法签名存在只能证明源级入口，不证明二进制/渲染/行为完整兼容。

## 逐项启用策略与待执行验证

1. 缺失插件：记录 MISSING，P0 的命令、设计校验仍可工作；需要模型的未来功能保持禁用。
2. 插件已装但版本/API/服务不符：记录 INCOMPATIBLE 或 UNVERIFIED 及原因，不猜替代方法；目标 1.21.11 尚无通过证据时不激活实例投影。
3. 在隔离 Paper 1.21.11 + Java 21 环境安装对应 SHA 构建物，记录服务端/插件 SHA256；执行根 API ready、模型加载失败/成功、spawn/bind/teleport/动画/despawn/世界卸载/重启与禁用清理契约。
4. 用真实默认客户端接受资源包，核验模型、动画、可见性和多人加入/退出；服务端 smoke 或 Mock 不能替代该步骤。
5. 核验事件回调线程，检查监听注销和错误隔离；所有 Bukkit/引擎操作只在其支持的线程执行，纯模拟只接收复制的有限数据。
6. Folia 需先取得上游自身区域线程支持与目标构建实机证据；在此之前明确阻断模型桥接，不能仅设置 folia-supported=true。
7. `BehaviorRegistry` 缺少公开注销生命周期：P10 前须向上游提出带 owner/token 的注册/注销契约并补测试，或限定静态服务器启动注册且禁用动态重载。

本审计没有执行上游 Maven 构建、Paper 1.21.11 实机或 Folia 测试；这些均保持未验收。
