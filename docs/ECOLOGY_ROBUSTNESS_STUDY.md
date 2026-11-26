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

使用包含当前模拟实现的 shaded 基础 JAR，以 source-file 模式运行：

~~~text
java -Xms16m -Xmx96m -Xss512k -XX:+UseSerialGC -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=96m -XX:ActiveProcessorCount=2 -cp <exact-plugin.jar> scripts/EcologyRobustnessStudy.java <new-output.json> 64 32
~~~

跨 JDK 复核将两个样本参数改为 4 4，使用新的输出路径。中断后可以在原参数后添加 --resume。工件、源码、plan、运行日志和结果的哈希随最终证据归档。

scripts/verify_ecology_robustness_study.py 独立重建种子序列与参数矩阵，检查完整场次、计划身份、旧样本隔离、预算/守恒、里程碑和跨 JDK 重复结果；它不会重新执行 Java 物理过程。Python 单元回归包含合法灭绝、缺失/重复/部分场次、计划变化和跨 JDK 差异拒绝。

这些观察不证明全部参数、全部 seed、无限期共存、玩家体验或生产容量。局部扰动导致灭绝本身是研究结果，不能修改模拟规则或默认值来让统计变绿。阶段门仍需对应的独立验收。
