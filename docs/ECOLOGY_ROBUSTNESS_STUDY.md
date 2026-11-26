# P5 v2 局部参数稳健性研究

本研究补充生命周期 v2 的有限样本证据，不选择新默认参数。生产默认程序与物理规则保持不变。既有 32 seed 默认/最高突变研究和灭绝对照仍按原工件身份保留。

## 预先固定的方法

- 使用当前 genesis-ecology/2，每场 1536 步，EcologyConfig.forDuration(1536)，封闭 Matter=1000，人口/出生/代数/账本预算不变。
- 抽样种子固定为 Java Random(2026112602L) 的 nextLong()；拒绝重复、四个边界 seed 以及旧研究的 28 个随机 seed。新种子列表在执行任何场次前写入只创建一次的 plan 文件。
- 默认组使用 64 个新 seed。每项单参数扰动使用其中前 32 个 seed，与默认组配对；不能将全部场次数相加为独立 seed 数。
- 逐物种单独改变 intake ±1、reproductionMass ±1、lifespanSteps ±25%（整数除法，最少改变 1）。其他物种、参数和配置保持不变。grazer 的寿命上调超过既有 512 上限，预先排除并记录，不能钳制成无变化组或另选替代值。
- 共 18 组：默认加 17 个有效扰动，预计 64 + 17×32 = 608 场。全部结果、灭绝、最低种群、首次灭绝时间、预算触顶情况和里程碑保留，不按存活结果挑选样本。
- 每一步检查守恒、人口恒等式及有限预算，终局执行完整回放验证。Java 25 使用相同种子前缀的 4+17×4=72 场作跨 JDK 完整结果复核；它是重复样本，不增加独立 seed 数。

研究工具为 scripts/EcologyRobustnessStudy.java，只读取编译后的模拟 API。输出已存在时默认拒绝运行，plan 在首场前以 CREATE_NEW 写入，进度报告以同目录原子替换发布。显式 --resume 要求原 plan 与重新生成的完整计划相等，已完成场次是无缺口的计划前缀，且重新执行最后一次原子检查点的完整结果相等。它不独立重算全部既有场次；最终另用 Python 校验器检查完整证据。运行错误保留先前结果与日志，不改成通过或隐藏失败。

## 可复现调用

后续新研究使用包装器固定 Java 启动程序、shaded 基础 JAR 和研究源码的字节身份。以下命令使用本次归档的原始 JAR，输出必须是新路径：

~~~text
python scripts/run_ecology_robustness_study.py --java <java-path> --plugin-jar docs/evidence/p5/robustness/artifacts/StarGenesis-4.0.0-SNAPSHOT.jar --expected-jar-sha256 63de78696d735ba45fa88f05f6a72b4c57317950c07876098878731ec73528c2 --output <new-output.json> --baseline-seeds 64 --paired-seeds 32
~~~

包装器在启动子进程前独占创建 `<output>.runtime.json`，记录解析后的路径、Java 启动程序/JAR/研究源码 SHA-256、JVM 参数与 seed 数。每段运行前后重新校验这些字节；显式 `--resume` 要求报告、plan 和原 runtime manifest 都存在且完整绑定相同，不会自动把旧研究纳入新包装器。更换路径、工件、源码、Java 启动程序或 seed 数均拒绝续跑。它继承标准输出和错误输出，worker 失败保留原报告与 manifest，最终研究结论仍须通过下述独立校验器。

同一输出的包装器通过独占创建 `<output>.run.lock` 拒绝并发续跑，正常退出或异常返回时仅释放自己持有的锁。进程崩溃可能留下锁；必须先确认原拥有进程已经结束，并检查原 report/plan/runtime，再显式删除确认为残留的锁后续跑，不能删除活跃进程的锁。此机制只协调该包装器，不约束绕过包装器的直接 Java 调用。前后哈希检查不覆盖整个 JDK，也不能检测检查间发生后又恢复的瞬时改写；不是文件认证或输入租约。

包装器调用的 worker 仍使用 source-file 模式。直接调用形式保留用于解释历史日志：

~~~text
java -Xms16m -Xmx96m -Xss512k -XX:+UseSerialGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=96m -XX:ActiveProcessorCount=2 -cp <exact-plugin.jar> scripts/EcologyRobustnessStudy.java <new-output.json> 64 32
~~~

跨 JDK 复核将两个样本参数改为 4 和 4，使用新的输出路径。中断后可以在相同包装器参数后添加 `--resume`；直接 worker 的历史续跑参数也保持兼容。工件、源码、plan、运行日志和结果的哈希随最终证据归档。

scripts/verify_ecology_robustness_study.py 独立重建种子序列与参数矩阵，检查完整场次、计划身份、旧样本隔离、预算/守恒、里程碑和跨 JDK 重复结果；它不会重新执行 Java 物理过程。Python 单元回归包含合法灭绝、缺失/重复/部分场次、计划变化和跨 JDK 差异拒绝。

~~~text
python scripts/verify_ecology_robustness_study.py --primary docs/evidence/p5/robustness/historical/java21.json --primary-plan docs/evidence/p5/robustness/historical/java21.json.plan.json --comparison docs/evidence/p5/robustness/historical/java25.json --comparison-plan docs/evidence/p5/robustness/historical/java25.json.plan.json --prior-validation docs/evidence/p5/robustness/provenance/prior-32-seed-validation.json --output <new-validation.json>
~~~

## 2026-11-26 完整结果

Java 21 主研究完成 608/608 场，Java 25 的 72/72 场重复结果逐字段相同；64 个新增 seed 与原 32 个验证 seed 无重叠。全部主研究样本达到 1536 步并通过 worker 完整回放验证，独立 Python 校验通过。默认配置保留三物种的是 **63/64**，其前 32 个配对 seed 为 **31/32**。seed `8108471355311695579` 的默认组在第 1360 步失去 decomposer；原始运行日志和报告均有此结果，另以同一 JAR/worker 重新执行该完整案例并逐字段核对相同。先前聊天中“新增默认种子全部存活”的中途描述不成立，应以此完整证据为准。

| profile | 1536 步时仍有三物种 / 场次 |
|---|---:|
| default | 63/64 |
| decomposer-intake-minus | 31/32 |
| decomposer-intake-plus | 31/32 |
| decomposer-reproductionMass-minus | 31/32 |
| decomposer-reproductionMass-plus | 31/32 |
| decomposer-lifespanSteps-minus | 25/32 |
| decomposer-lifespanSteps-plus | 32/32 |
| grazer-intake-minus | 32/32 |
| grazer-intake-plus | 31/32 |
| grazer-reproductionMass-minus | 31/32 |
| grazer-reproductionMass-plus | 31/32 |
| grazer-lifespanSteps-minus | 32/32 |
| producer-intake-minus | 31/32 |
| producer-intake-plus | 31/32 |
| producer-reproductionMass-minus | 31/32 |
| producer-reproductionMass-plus | 32/32 |
| producer-lifespanSteps-minus | 26/32 |
| producer-lifespanSteps-plus | 32/32 |

总计 24 场失去至少一个物种，逐 seed 的首次灭绝时点、最低种群和配对四格结果保存在 `docs/evidence/p5/robustness/validation.json`。这些组共享同一 seed 子集，不能把表内比例当作彼此独立的实验，更不能以个别 32/32 的扰动组选择新默认值。主研究中 608 场的总和也不能取代独立 seed 数。

## 归档与来源边界

`docs/evidence/p5/robustness/` 保留原始 report、先写入的 plan、追加运行日志、历史命令快照、原 launcher 字节、确切 JAR、worker/模拟源码快照和 SHA-256 manifest。原始运行使用 `.work/ecology-robustness/run_studies.py`，Java 21 在 6、23、96 场后从检查点续跑；保存的命令也披露曾使用不同 JIT/代码缓存设置。这些参数不构成性能证据。

历史 launcher 仅在自身启动时核对 JAR 哈希，没有为每段保存源码或 Java 字节绑定。归档时核对 worker 与先前 `dynamic-lifecycle/summary.json` 已记录的源码哈希及提交 `f4a21da` 相同，37 份模拟生产源码也与该提交逐字节一致；原构建记录已记载相同 JAR 哈希。这些事后核对不能补造当时不存在的每段输入租约或受认证的源码到二进制证明，历史报告旁不会补写新的 runtime manifest。

新包装器另有 17 项 Python 回归，独立校验器有 18 项回归，均通过。另用确切归档 JAR/当前固定 worker 真实执行一个 18 场包装器样本并与原研究对应案例逐字段相等，再执行已完成报告的 `--resume`，确认报告和 plan 字节不变。这 18 场和默认灭绝反例复跑属于归档后的重复验证，不增加独立样本，也不能声称历史 608/72 场使用了新包装器。运行日志、绑定与命令分别位于 `verification/` 和 `provenance/`。

这些观察不证明全部参数、全部 seed、无限期共存、玩家体验或生产容量。局部扰动导致灭绝本身是研究结果，不能修改模拟规则或默认值来让统计变绿。阶段门仍需对应的独立验收。
