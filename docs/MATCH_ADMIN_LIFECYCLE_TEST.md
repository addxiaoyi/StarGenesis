# 工厂与 Arena 终局管理测试

`scripts/server_match_admin_smoke.py`在同一隔离目录启动三次实际Paper或Folia服务器。四位固定普通玩家和独立管理员`SGReview`通过客户端命令完成邀请、终局比赛、退出重组、删除旧历史和重启恢复。所有玩法修改都经过插件命令；脚本只读取、复制存档证据，不直接改写比赛、roster或发布规则。

需要Java21、Node22或更高版本、支持Minecraft1.21.11的Mineflayer、官方服务器JAR、已构建插件和预先接受的`eula.txt`。目录必须没有已有比赛或本流程证据。示例：

```sh
python scripts/server_match_admin_smoke.py \
  --kind paper --java /path/to/jdk/bin/java \
  --server-jar /path/to/paper.jar \
  --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar \
  --node-modules /path/to/node_modules --directory .work/match-admin-paper \
  --port 25679 --heap-mb 1024
```

`--libraries-from`可复用现有服务器依赖。Folia传入对应JAR和`--kind folia`，新目录使用两个调度线程。`--jvm-arg`可重复提供JVM参数；记录的512MiB测试堆和限制编译器/GC开销的参数仅用于隔离功能检查，不作为生产配置或性能结论。服务器只监听回环地址，离线身份固定为`SGMatchAlice`、`SGMatchBob`、`SGMatchCarol`、`SGMatchDave`和`SGReview`，8个登录名额实际连接5人。管理员由现有harness在该隔离服务器启动后授予OP。

1. `prepare`：未接受邀请时硬断开邀请方，两服务都拒绝接受旧邀请；同UUID重连后重新邀请成功。普通玩家创建4×4、2轮Arena规则，由独立管理员审核发布。开赛后管理员不能清理活跃场次。工厂订单经过确定性故障与退款补偿，继续完成100步；Arena完成两轮并保留管理员观战身份。普通成员不能删除共享终局历史，回放通过后关闭服务器。
2. `reorganize`：重启和登录后先逐文件验证原字节。四人退出旧组织/队伍，按Alice+Carol与Bob+Dave重新组队并创建新场次；旧比赛仍保持原roster与文件。新Arena封存Alice动作，再选择旧历史并硬断线重连，恢复优先指向新活跃场次且pending保持。管理员只删除两份已结束历史，新的组织、队伍、活跃比赛原字节保持；旧历史选择和原观察者dashboard失效，活跃清理继续拒绝。
3. `verify`：再次重启、登录和回放，确认已删历史没有恢复，新roster、活跃场次和pending仍在。所有只读操作和关服后，比赛与发布设计文件保持第二次关闭时的原字节。

每次退出都要求harness确认插件干净关闭，四个服务目录锁可由宿主重新获取。`match-admin-evidence/<phase>/`保留控制台、客户端输出、存档原字节及哈希；删除前的两份终局文件另外保存并与首轮关服文件逐字节核对。失败也归档，不能只保留最后一轮结果。

CI运行证据检查器单元回归：拒绝已删比赛复活、存档丢失、规则文件改写、pending丢失/冒名、旧组织配对、未终局记录或队伍不符。完整服务流程不在CI自动下载Minecraft或接受EULA。

本流程验证真实命令入口的正常终局管理和重启恢复，不是队列饱和、磁盘故障、强制崩溃、跨Folia区域、插件热重载、生产性能或真人公平性验收。连接代次与满队列清理由`PLAYER_COMMAND_LIFECYCLE.md`中的单元回归覆盖。
