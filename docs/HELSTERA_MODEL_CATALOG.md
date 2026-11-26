# Helstera 只读模型桥接

后续已增加[有界实验室投影](HELSTERA_LAB_PROJECTION.md)，要求固定上游92982925加清理补丁。当前构建使用 `prepare_helstera_api.py --verify-runtime`。下文保留a2969dfa只读批次的历史证据及当时的投影阻断原因，不能把这些旧证据当作新投影验收。ModelCatalog接口本身继续只读。

基础插件新增 `/genesis model status` 与 `/genesis model inspect <model ID>`，权限 `stargenesis.models` 默认 OP。单独安装 `StarGenesisHelstera` 才提供模型查询；基础 StarGenesis JAR 不包含 Helstera 类型，也不要求安装该扩展。查询只复制已加载模型的 ID、scale、骨骼数量、碰撞盒尺寸和至多256个动画名，命令最多显示16个动画名。没有模型加载、生成、行为注册、库存或结算操作。

扩展仅允许 Paper 1.21.11，`plugin.yml` 没有 Folia 支持声明。提供方名必须为 `helsteraMobs`、插件/API 版本必须为 `1.0.0`。每次请求在主线程重新获取提供方、根 API 和 ModelRegistry，不跨请求缓存服务或模型对象；API 未就绪、提供方停用/升级、模型未加载和非法元数据不会返回旧结果。提供方或 StarGenesis 停用时注销服务、关闭旧引用。`/genesis debug capabilities` 现在查询当前提供方状态；唯一可激活的新能力为 `model.inspect`。

源码审计及测试固定 [helsteraMobs a2969dfa](https://github.com/addxiaoyi/helsteraMobs/tree/a2969dfaf83cd35e642147f4bd7743ae04d34e7a)。版本字符串检查不是安装 JAR 的密码学认证；运行方须使用证据中的构建物或重新验证替代构建，不能把其他同名1.0.0产物自动算入本次实服证据。

## 构建

```text
python scripts/prepare_helstera_api.py
./mvnw -B -ntp -Phelstera-bridge verify
```

Windows 使用 `mvnw.cmd`。准备脚本下载固定提交源码，只提取锁文件中的122份实际 parent/API/license文件并逐个校验Git blob SHA-1，运行上游API的222项测试，再以带完整commit的私有本地Maven坐标安装。可用 `--archive <exact-commit.zip>` 复用下载；每次编译从新的临时源码目录开始。Java21及25 CI均执行准备和桥接profile。默认reactor仍可在没有Helstera依赖时构建基础插件。

产物为基础 `stargenesis-paper-4.0.0-SNAPSHOT.jar` 和可选 `stargenesis-helstera-4.0.0-SNAPSHOT.jar`。扩展依赖均为provided，不把上游API或StarGenesis契约重复打包；Bukkit通过显式依赖加载同一份服务类型。上游完整插件另行在隔离目录从固定源码构建；其package使用跳过测试参数，不代表整套上游验证通过。

## 投影仍关闭的具体原因

上游 `InstanceManagerImpl.createInstance` 先登记实例，`spawn` 随后调用 renderer。`HelsteraSpawnFailureProbe.java` 在真实上游InstanceManager与ModelDefinition上向渲染边界注入异常，观察到抛错后 `activeCount=1` 且实例仍有效。`DisplayRenderer.createVisuals` 直到所有实体创建结束才把Visuals放入管理表；公共实例API无法保证回收中途创建的全部实体。本探针证明实例登记未回滚，没有执行实服实体泄漏测试。

此外，当前 `ModelRegistry.load` 的实现仅异步解析/校验并返回Optional，注册由调用方在主线程执行；这与接口“完成后注册”的注释不同。实际桥接不调用load；实服fixture明确区分解析完成、主线程注册、卸载和重载。`BehaviorRegistry`仍缺少通用注销/token；上游没有Folia支持。因此 `entity.project`、`behavior.register`、`event.subscribe` 均保持inactive。

本批证据见 `evidence/p10/model-catalog/summary.json`。纯API测试替身、实际运行时故障注入和真实Paper提供方生命周期分别记录；模型外观、资源包、动画、多人和Folia第三方兼容均不在本轮范围内。

最终Java21/25各通过564项测试（75个类，失败/错误/跳过为0），13项Python、6项Node和两套JDK共8个跨进程/签名探针通过。首次Java21完整复核在24 MiB代码缓存耗尽后出现3项测试错误；保留失败日志，并以64 MiB完整重跑通过。最终Java21基础JAR SHA-256为`7287928489d7ccaa546235599dd1202dc20c8f291d08da669ddb12f4e25a14a0`，扩展为`4b95601b69ff4a93d2611edf37502ef9d27445b52872cd4c8a68870ccf3a5ac4`，真实Paper检查使用的正是这两个工件。

同一基础JAR也在没有Helstera/扩展的Folia1.21.11-14上通过22项控制台启停检查。该结果仅证明基础插件的可选依赖隔离，不是Helstera的Folia兼容证据。
