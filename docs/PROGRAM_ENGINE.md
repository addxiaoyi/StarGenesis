# P3 有界行为图引擎

`Program` 是不可变、有向无环的行为图。`ProgramCompiler` 按节点 ID 进行稳定拓扑排序，生成整数索引的 `TypedProgram`，检查每个输入的值类型与连接数量。v1 JSON 格式保持可读取，已签名示例仍可载入。

图最多包含 256 个节点、512 条连线。每个节点的执行成本为一个指令，单次执行预算为 1..4096。程序不可访问文件、网络、Bukkit、反射或任意类加载。每个 tick 的所有感知读取同一个不可变 `ProgramWorldView`。解释器完成后才提交动作。算术溢出、除零或预算耗尽会清除本轮命令，不会部分推进世界。

| 类别 | 操作 | 签名或输入 |
|---|---|---|
| 传感 | SENSE | 读取 x、y、tick、背包/共享库存、相邻资源或阻挡 |
| 常量 | CONSTANT、VECTOR | 有类型整数/布尔/资源或单位正交向量 |
| 布尔 | COMPARE、AND、OR、XOR、NOT、EQUAL | 定长类型值和布尔运算 |
| 整数 | ADD、SUBTRACT、MULTIPLY、DIVIDE、REMAINDER、MIN、MAX、ABS、NEGATE、CLAMP、SELECT | `long` 精确溢出检测，除法向负无穷取整，模数为非负 |
| 向量 | VECTOR_SELECT | 按布尔值从两个固定正交方向中选择 |
| 行为 | MOVE、HARVEST、DEPOSIT、WAIT | 转换为 P1 网格模拟动作 |

程序执行器暴露继续、单步、节点断点和有序节点跟踪。`/genesis program` 运行一个内置采集图并把动作交给玩家自己的网格实验室。玩家自定义行为图编辑与保存尚未实现。

## 线程和数据边界

编译器、IR 与解释器只依赖纯 Java 类型和 API AgentId。Paper 适配器将线程所有权内的网格快照复制为不可变 ProgramWorldView，将完整成功结果转成 StepInput，并在同一会话中推进网格。程序定义不更改、不能写入其他玩家数据。P1 回放日志与进程崩溃耐久仍单独开发。
