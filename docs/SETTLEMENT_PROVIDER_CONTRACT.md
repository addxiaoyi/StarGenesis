# 结算接入与恢复合同

当前真实交易入口关闭。以下是P9 adapter的接入条件，不代表任何真实provider已通过验收。现有StarCore公开转账没有外部幂等键，不能用本地文件记录代替外部资产系统的原子幂等机制。

## 外部结果与幂等键

付款、交付、补偿各使用一个由saga UUID与phase固定派生的key。相同key必须持久绑定完全相同的source、target、asset、assetId和amount；不同payload不得复用。去重记录与资产变化必须由provider原子执行并在重启后可查。不能先转账、再另写本地“已执行”标记。

| API结果 | 必须保证的事实 |
|---|---|
| inspect: APPLIED | 相同完整operation已生效，结果已持久记录 |
| inspect: NOT_APPLIED | 权威确认没有生效，且没有仍可能随后生效的在途调用 |
| inspect: UNKNOWN | pending、查询不可用、结果冲突、记录保留期不足或其他无法证明结果的情况 |
| apply: APPLIED | 完整操作及其幂等结果均已持久提交 |
| apply: NO_EFFECT | 本次操作确定未产生副作用，也不会在返回后晚到生效 |
| apply: INDETERMINATE | 无法确认结果；后续须先查询，不能盲重试 |

coordinator把查询异常/null当作UNKNOWN，把调用异常/null当作INDETERMINATE。未知查询进入MANUAL_REVIEW，不确定调用保留可查询的原状态。查询到已应用的历史操作时，即使授权已过期也恢复该结果；过期仅阻止新副作用。交付未发生而付款已发生时进入补偿，已确认交付完成后不能因授权过期再次退款。

Capability的两个布尔值和evidenceId只是adapter声明，不能替代上述行为的真实故障验收。不能把最终一致的“暂时查不到”映射为NOT_APPLIED。

## 仓库和线程范围

`SettlementSagaRepository.withExclusiveSaga`同步执行整个begin/advance。在同一范围内读取当前状态、查询外部结果、检查授权、调用provider并提交新revision。实现必须协调所有共享同一存储的实例与进程；只在最终replace时做CAS无法阻止并发过期分支抢先提交。

Paper使用JVM分片锁和文件锁；异常和进程退出释放锁。相同saga可重入读写，嵌套不同saga拒绝以避免锁顺序死锁。外部调用必须有有限超时，任何异步外部工作仍须满足上表的在途查询语义。文件锁不替代provider端幂等性，也不保证网络文件系统或跨主机部署已验收。

新写入只能从PREPARED/revision=0开始。普通替换固定原请求与permit，保留现有状态图和revision竞争检查，终局及MANUAL_REVIEW不能由普通replace重开。

## 显式复核与对账

`SettlementReviewCoordinator.reconcile`提供可测试的复核协议，当前没有注册玩家/OP命令或生产授权adapter。调用方提交review UUID、saga UUID、独立审核员UUID、预期revision和1–256字符case reference，不提交想要的资产结果。买方、卖方和原actor不能审核本交易；独立的`SettlementReviewAuthorizer`必须按实际部署策略授权，并返回绑定本次审核的policy/evidence ID。交易原permit保持不变，旧交易授权过期不阻止查询历史事实。

整个复核在相同saga的JVM/OS排他锁内完成。先核对身份、revision、历史预算与审核授权，再以原有完整payload和幂等key查询付款、交付、退款。复核不调用`apply`。只有以下一致组合能够离开MANUAL_REVIEW：

| 付款 | 交付 | 退款 | 保存后的状态 |
|---|---|---|---|
| NOT_APPLIED | NOT_APPLIED | NOT_APPLIED | FAILED |
| APPLIED | APPLIED | NOT_APPLIED | COMPLETED |
| APPLIED | NOT_APPLIED | APPLIED | COMPENSATED |
| APPLIED | NOT_APPLIED | NOT_APPLIED | COMPENSATION_REQUIRED，后续由原coordinator执行幂等退款 |

任一UNKNOWN、异常/null结果或矛盾组合继续保持MANUAL_REVIEW并保存此次未解决的复核记录。此前已确认APPLIED的操作不能因后来的NOT_APPLIED而被抹除。旧v1停止状态仅接纳原coordinator可能产生的三个来源：revision 1/2的EXTERNAL_OUTCOME_UNKNOWN，以及revision 3的COMPENSATION_OUTCOME_UNKNOWN或COMPENSATION_REJECTED；后两条路径都已经确认付款。来源不明的旧文件拒绝自动对账，不能仅凭人工填写“成功”或“失败”放行。

每条记录保留前后完整snapshot、审核请求/授权、provider capability evidence、逻辑tick、之前确认的操作与三项新查询结果。状态与有序记录写入同一个schema-v2 envelope，一次原子替换发布；后续普通退款恢复必须保留记录。最多16条复核且总文件仍限64 KiB，合法旧permit较大时可能更早达到字节上限，失败不截断历史。相同review ID/相同请求重试重新检查审核权限，然后返回原记录，不重复查询或改写；复用ID修改请求或过期revision拒绝。

记录中的provider evidence是adapter的能力声明，审核授权证据由部署策略提供；它们都不是provider签署的逐笔收据，也不独立证明真实资产变化。磁盘历史进行结构、身份、状态和顺序校验，不是对恶意文件重写的数字签名证明。生产启用仍需要实际provider审计、审核权限接入、操作入口及运营流程验收。

## 可执行验证与剩余验收

单元测试覆盖付款/交付在途时的过期恢复、同请求并发begin、其他saga推进、真实文件写盘失败后付款/交付/补偿重试，以及旧v1已付款记录恢复。provider为内存测试替身，不是真实资产验收。

本地跨进程文件锁探针使用临时目录和两个受限JVM，分别测试正常释放与终止持锁进程后等待者继续：

```sh
python scripts/test_settlement_process_lock.py --java-home <JDK> --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --output .work/settlement-process-lock.json
```

实际接入还须记录provider/API版本、授权策略、幂等保留期与查询一致性证据，并在其测试环境验证重复/并发key、payload冲突、进程重启、超时后晚到、三条腿的提交后本地写盘失败、退款失败和人工复核。只有实际API和故障证据满足合同，才能提出启用真实交易的变更。
