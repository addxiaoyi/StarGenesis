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

新写入只能从PREPARED/revision=0开始。替换固定原请求与permit，保留现有状态图和revision竞争检查，终局不能由普通replace重开。MANUAL_REVIEW仍是停止状态，生产人工复核需要独立的授权、结果证据与审计流程，当前没有启用该入口。

## 可执行验证与剩余验收

单元测试覆盖付款/交付在途时的过期恢复、同请求并发begin、其他saga推进、真实文件写盘失败后付款/交付/补偿重试，以及旧v1已付款记录恢复。provider为内存测试替身，不是真实资产验收。

本地跨进程文件锁探针使用临时目录和两个受限JVM，分别测试正常释放与终止持锁进程后等待者继续：

```sh
python scripts/test_settlement_process_lock.py --java-home <JDK> --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --output .work/settlement-process-lock.json
```

实际接入还须记录provider/API版本、授权策略、幂等保留期与查询一致性证据，并在其测试环境验证重复/并发key、payload冲突、进程重启、超时后晚到、三条腿的提交后本地写盘失败、退款失败和人工复核。只有实际API和故障证据满足合同，才能提出启用真实交易的变更。
