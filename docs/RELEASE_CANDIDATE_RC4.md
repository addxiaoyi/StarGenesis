# RC4 候选构建与验证

`4.0.0-rc.4` 汇入 RC2 稳定性续开发：持久化与恢复、P9 显式复核、生态 v2、工作线程目录所有权、编辑会话限制，以及 P6/P8 邀请和玩家连接生命周期。工作树的 Maven reactor 继续使用 `4.0.0-SNAPSHOT`；候选版本只写入隔离源码副本。

候选来源为提交 `3328c03c63440b287e0da1fd29480e4e258b6064`，Git 文件树为 `2e63ada4548cf5a4fc6e066a3048e6f905ed617c`。本地原提交 `deedcc9` 因 HTTPS 连接失败改用 GitHub 连接器同步，远端提交的文件树、父提交与本地相同。原提交保留在本地 `refs/backup/match-admin-local-deedcc9`；没有强制推送。

## 构建结果

同一 Windows 主机上，Microsoft JDK 21 的两份独立源码副本分别执行 `clean verify`，每次 539 项测试、70 个测试类全部通过，失败、错误和跳过均为 0。使用 `--fork-count 0`，实际 JVM 参数和构建日志保留在证据中。

| 项目 | 两次构建的一致结果 |
|---|---|
| 源码输入树 SHA256 | `f85b7d3ba1fda1d940c28da0fa642483028dff2555df29b4ad4434e4ce755edd` |
| JAR SHA256 | `a1a6c3eceaf7a34da9bbbe846d1c95f0dbac185c370491d876072873638d5b20` |
| JAR 大小 | 3,062,279 字节 |
| SBOM SHA256 | `0859ff83bb6a2d2766f63e05162bd1bd0552a56cee8a3d878e56791b5bf95382` |
| SBOM 格式 | CycloneDX 1.6，61 个组件 |

本地工件位于 `.work/releases/4.0.0-rc.4/StarGenesis-4.0.0-rc.4.jar` 和同目录的 `sbom.json`。这证明同主机、同 JDK 的两次构建一致，不承诺不同 JDK 或主机的二进制哈希相同。

同一候选 JAR 在 Windows Java 21/25 下分别通过工作目录跨进程锁、结算跨进程锁、发行签名和设计包签名自检，共八项。签名工具使用并删除临时测试私钥，验证篡改拒绝；没有使用正式发行方私钥签署此候选。

## CI

[来源提交的 workflow 45748819405](https://github.com/addxiaoyi/StarGenesis/actions/runs/45748819405) 在 Linux Temurin Java 21/25 下均成功。逐项核对原始日志，每个任务通过 539 项 Java、11 项 Python、6 项 Node 回归及四项锁/签名探针，模块边界检查通过。

CI 构建的是同来源提交的 SNAPSHOT。其日志不能替代上述 RC4 工件本身的验证；RC4 使用独立候选构建与探针结果。

## 实际服务端

同一 RC4 JAR 在 Paper 1.21.11-132 和 Folia 1.21.11-14 上各启动一次，连接五名自动客户端，分别用时544.48秒和562.77秒。每端23项控制台检查、12项客户端流程均通过，覆盖P1–P8基础工作流、P9真实结算保持关闭和P10临时测试公钥导入/轮换/撤销；工厂故障订单补偿、Arena硬断线后pending动作恢复及回放均通过。正常退出后，每端四个工作目录锁均可重新获取。

生态测试包含四组参数与八个固定seed。两平台八个seed均推进到500步、保持Matter=1000且通过回放，三物种存活为8/8；没有据此扩大为全参数平衡结论。这次每端只有一次启动，既有SNAPSHOT的多次重启证据继续保持原工件标识。

完整摘要与哈希位于 [RC4证据](evidence/p11/rc4-recovery/summary.json)。仓库保存40份构建/CI/探针/实服日志和结果，并记录12份相关源码的SHA256。实服插件存档完整内容留在本地 `.work/rc4-live/`，归档中仅保存存档文件哈希清单；来源CI保留标明筛选范围的日志摘录。

## 验收边界

RC4之后的代码审查发现 `radio ack` 在多个协议共享发送者/tick/sequence时会选择第一条消息。后续SNAPSHOT已增加歧义拒绝和显式协议参数，见`RADIO_ACKNOWLEDGEMENT.md`；本页哈希对应的RC4仍保留旧行为，不作为此修复的交付包。

候选未创建 GitHub release/tag，未部署生产环境，也未正式签署。P9 真实结算继续关闭；真实 provider、生产审核授权、运营 trust root、第三方 bridge、真人玩法/公平性/留存和生产容量等阶段门仍需相应证据。单元测试、自动客户端和临时密钥不替代这些验收。
