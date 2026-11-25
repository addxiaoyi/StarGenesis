# 生态引擎 v2 生命周期

`genesis-ecology/2`用于新建生态舱。旧回放显式由`genesis-ecology/1`重演，既有物质、程序、输入和commitments不迁移。外层玩家文件与EcologyReplay继续使用schemaVersion=1；engineVersion区分模拟语义。未知引擎或仅修改版本标签的文件拒绝恢复。

## 规则与玩家参数

v1允许新生个体立即繁殖，个体持续摄入且只在死亡时返回储量，并按world ID派生的UUID分配处理次序。已归档的长期反例显示，部分种群会耗尽8代额度；另一世界ID也会改变相同参数/种子的存活结果。

v2采用以下生命周期，仍只转移已有matter：

1. 应用本tick的物种程序修改，保持已有个体年龄、代数和最近成功繁殖年龄。
2. 按seed、tick和个体出生序号派生的确定性次序处理个体，逐个增长年龄、摄入和死亡。
3. 所有本tick死亡的个体释放空位后，存活个体按相同次序竞争繁殖。新生个体到下一tick才参与进食或繁殖。

| 参数/状态 | v2语义 |
|---|---|
| intake | 每tick摄入速度上限。物质储量达到reproductionMass后停止摄入 |
| reproductionMass | 触发繁殖的储量阈值，同时限制新增摄入。调低后不会删除已有储量 |
| lifespanSteps | 成熟年龄为向上取整的寿命一半；成功繁殖之间至少相隔向上取整的寿命四分之一 |
| 繁殖 | 达到成熟期、间隔、储量及全部预算时，父体转移1 matter给子体；父体至少保留1 matter |
| 死亡 | 年龄达到当前寿命时先死亡，不在同tick繁殖；producer/grazer储量回到detritus，decomposer回到soil |
| mutationPermille | 子代有界突变概率。原三种trait、上下界和seed确定性保留 |

繁殖间隔只在成功出生时更新。失败争夺空位不消耗一次繁殖机会，修改程序或重启不清除最近成功繁殖年龄。修改寿命后，成熟期与间隔按当前程序计算；这允许玩家显式改变策略。寿命1的个体会在首次繁殖前死亡。

新默认程序沿用已有长周期研究的intake/储量/寿命组合，并保留原默认突变率：producer`(6,4,320,150)`、grazer`(4,24,512,100)`、decomposer`(3,4,320,80)`。默认时长仍为500，用户可选择1–1536。存活是实验结果，不由规则强制保底；不补种、不增发matter、不重置代数。

## 身份、随机选择与回放

world ID仍参与个体UUID、事件EvidenceId与完整状态/事件哈希，不能通过重命名转移所有权或复用回放。v2的处理优先级、猎物选择和子代突变仅使用seed、tick、稳定个体序号及出生序号，不使用world ID或UUID。初始个体按producer/grazer/decomposer顺序获得0–6序号；后代从7递增。同seed/config/input在不同world ID下应具有相同物质与种群轨迹，但commitments不同。

处理优先级为`mix64(seed ^ mix64(tick) ^ mix64(ordinal) ^ 0x3c6ef372fe94f82bL)`，按无符号long排序、ordinal兜底。猎物候选按ordinal排序，选择盐为`0x6a09e667f3bcc909L`；突变盐为`0xbb67ae8584caa73bL`。具体mix64与编码以`EcologyWorld`为版本合同。新状态哈希额外绑定每个个体的ordinal和lastBirthAge。v1继续使用原UUID次序、随机输入与哈希字段，不包含这些新增值。

## 预算与消费者

人口32、代数8、默认出生512、时长1536、账本100,000次的硬边界不增加。每个已有个体每tick最多一次摄入，加一次死亡或繁殖转移；死亡和繁殖互斥，所以原`maxTicks × maxOrganisms × 2`证据预留仍足够。新生个体当天不增加处理工作。账户上限由初始7加最多2048个出生账户及soil/detritus约束。事件仍最多128/tick，历史文件仍4 MiB。

消费者为EcologyReplayVerifier、EcologyService/Persistence和纯模拟研究脚本。恢复与验证按回放中的engineVersion构造世界；旧有搜索脚本显式选择v1，`EcologyLongRunStudy`默认v1且可在最后传`v2`。新`EcologyLifecycleStudy`验证v2默认参数、最高突变率与灭绝对照。Paper面板显示当前默认程序的摄入储量上限、成熟年龄和间隔；实际突变个体按其继承程序执行。

新旧引擎均保留物质守恒、原所有权、未知版本拒绝和写盘失败后精确重试的要求。默认/对照研究仅构成有界纯模拟证据，不能替代实服长局、真人体验或无限期生态平衡验收。
