# Helstera 实验室模型投影

可选扩展将自己的 P1 资源实验映射为 Paper 世界中的临时模型。位置、AgentId、资源、得分和回放由实验室 worker 决定；显示实体不会回写模拟。仅支持 Paper 1.21.11，不是 Folia 桥接、模型外观验收或 P10 阶段通过声明。

## 使用

安装基础 StarGenesis、StarGenesisHelstera，以及下文的修补版 helsteraMobs。提供方须事先加载模型；StarGenesis 不自动加载模型、创建资源包或注册行为。

```text
/genesis model inspect <model-id>
/genesis lab start 42 2 commands
/genesisprojection start <model-id>
/genesisprojection status
/genesisprojection stop
/genesis lab stop
/genesis lab resume commands
```

`stargenesis.projection` 默认 OP，仅能预览自己的运行中实验。预览固定于启动位置附近，一个模拟格对应一方块，附近玩家可见。commands 模式与 status 不打开菜单；Paper默认仍使用菜单，Folia保持命令模式。

实验结束/停止、断线、权限撤销、切换世界、服务失效、模型卸载/替换和插件停用会停止预览。重新开始、恢复和回放都有新的投影会话身份；不跨重启自动恢复。

## 预算与失败处理

最多4场、每场1–4个Agent、每模型1–16个骨骼，总计128个实体。每Agent预留“骨骼数量+1载体”，首项创建前完成全场准入。scale不超过4，静态骨骼变换的保守半径不超过16格；每次检查完整覆盖范围的区块、世界边界和高度，不为显示加载区块或修改方块。这不是资源包纹理/多边形复杂度或生产性能保证。

载体不碰撞、无重力、不可受伤、不拾取物品；全部显示不持久保存，不生成Interaction、名称牌或血条，关闭自动动画，不启用AI、挂接物和行为注册。稳定Agent UUID映射到临时ModelInstance；新帧会话、模拟世界、尺寸和Agent集合必须一致，tick不倒退，同tick不可改变位置。重复帧不重复创建。所有实体操作限主线程。

创建/移动失败清理整场显示。部分创建异常携带失效实例的清理句柄；清理尝试全部对象，仅保留失败项，成功才释放整场额度。未完成清理阻止新场次，tick/stop/close可重试。提供方违反契约，抛出不带句柄的创建异常时不能证明没有副作用，保留预算并阻止新创建直到重启，不报告清理成功。VM崩溃、进程终止与永久失败不承诺内存恢复。

## 固定依赖

基于 [helsteraMobs 92982925](https://github.com/addxiaoyi/helsteraMobs/tree/9298292538dc9d7c5ee800ff7e03f4c10e34ba97) 加 `scripts/patches/helstera-owned-visuals.patch`，MIT许可随补丁保留。这是StarGenesis维护的依赖补丁，未声称上游main已合并。

补丁把实例发布推迟到渲染成功后，从spawn初始化回调开始记录归属；失败实例退出活动列表，失败清理保留句柄。移除直接使用拥有的实体对象，涵盖UUID查询失效的卸载对象，不移除调用方绑定的基础实体。创建回调重入清理时延后到World.spawn返回/抛错后回滚。另修复展示载体移动、同步传送拒绝，并增加关闭自动动画选项。

新API `supportsOwnedVisualCleanup()` 默认false。旧提供方没有方法时仅保留只读目录，不注册投影。版本/API/能力检查不是JAR密码学认证；替代构建须重新验证。绑定实体属性、动画事件、其他上游功能、行为注销、资源包和其他Paper版本不在保证内。

```text
python scripts/prepare_helstera_api.py --verify-runtime
./mvnw -B -ntp -Phelstera-bridge verify
```

准备脚本验证固定Git blob与补丁SHA-256，在全新目录应用补丁，实际编译API，并以commit+补丁hash作为本地Maven版本。`--verify-runtime` 测试API224、core47、runtime45、renderer14，共330项；不替换服务器插件。

完整提供方从相同提交的干净checkout执行 `git apply <absolute-path-to-patch>` 后运行Maven `clean package`。本机完整package使用 `-Dmaven.test.skip=true`，四个相关模块另行测试；没有整套上游测试/SpotBugs通过声明。Java21编译大型插件使用 `-Xss1m`，首轮256KiB栈失败日志保留。

必须使用clean构建：本轮发现增量package虽返回成功，最终JAR仍带旧renderer。`scripts/verify_helstera_provider.py`将实际打包的161个相关类与四个模块编译输出逐字节比较，并检查重入清理标记；三个被上游exp4j重定位改写的NumericExpression类单独记录编译/打包hash，不能称为字节一致。最终服务器测试使用通过核对的提供方，旧工件的历史结果不作为最终补丁验收。

## 证据

单测覆盖生成/传送异常、清理重试、重入关闭、模型替换、预算/坐标/线程与快照身份。实际上游回归先复现12项失败；日志、源码和工件hash见 `evidence/p10/lab-projection/summary.json`。

`server_helstera_catalog_smoke.py --probe projection` 用实际Paper、解析器、manager和renderer，以合成不可变帧检查实体；`--probe lab-projection` 进一步使用真实worker与固定离线客户端命令。它们不能证明资源包外观、动画、真人体验、长期负载、热重载或Folia第三方兼容。旧只读证据保留于 `HELSTERA_MODEL_CATALOG.md`。
