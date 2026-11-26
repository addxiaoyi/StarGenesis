# P4 实服重启恢复测试

`scripts/server_radio_lifecycle_smoke.py` 在同一隔离目录顺序启动三个服务器进程。两个离线客户端使用固定名字和UUID，按正常命令发送消息和领取契约，服务端通过控制台正常关闭；每次启动的日志、客户端结果和关闭后的`radio-state.json`单独归档。

首次实服探针发现文档中的`radio send contract ...`无法执行：控制器直接查询名为`contract`的协议，而协议目录只有`genesis:radio`和`genesis:service`。`RadioSendCommand`现将发送子命令中的`contract`映射到固定`genesis:service`，保留旧的完整协议ID写法。原始Paper失败日志、原解析逻辑及两项失败回归与修复结果一同保留。

| 启动 | 检查 |
|---|---|
| prepare | 普通消息poll后仍未确认；另一份offer未领取时保存；正常关服 |
| resume | UUID相同，在线端点为空，需重新join；消息原sender/tick/sequence/payload重投；领取offer后accept/fulfill完成；重复/冲突sequence拒绝重复或覆盖；确认后硬断线重连为空；无效契约消息保留；另存待超时offer |
| verify | 已完成契约不重放；无效消息原envelope仍在；待超时offer仅在expiresAt+1产生一次事件；人工ack只移除该无效消息；最终六条消息均确认，保留十二个radio事件和五个契约事件 |

协议的超时为200个**服务器逻辑tick**。服务器停机期间不前进；启动运行、玩家离线或尚未join时仍会前进。脚本允许时钟前进与按期产生超时事件，同时逐项检查此前消息、确认、pending、契约收件记录与事件历史前缀。不同进程的Map显示顺序与服务器时序可能不同，不要求日志字符串或整个radio文件跨平台逐字节一致。

重现前先构建插件，提供官方Paper/Folia JAR、Node客户端依赖，以及该隔离目录中已有的已接受`eula.txt`。脚本拒绝覆盖已有radio存档、客户端状态或本轮证据目录。

```text
python scripts/server_radio_lifecycle_smoke.py --java <Java21> --node <Node> \
  --server-jar <Paper-or-Folia.jar> --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar \
  --node-modules <mineflayer-node_modules> --directory <fresh-accepted-directory> \
  --kind paper --port 25677 --heap-mb 512 \
  --jvm-arg=-XX:+UseSerialGC --jvm-arg=-XX:TieredStopAtLevel=1 \
  --jvm-arg=-XX:ReservedCodeCacheSize=64m --jvm-arg=-XX:ActiveProcessorCount=2
```

CI运行两项Python和两项Node证据校验回归，拒绝未覆盖客户端观测的保存、回退时钟、丢失pending、改写消息/事件、遗漏或伪造超时。Java回归通过真实`RadioService`验证文档命令完成履约和恢复，并继续校验协议安装、负sequence、非法字段、规范UUID与原radio线格式。

2026-11-26，Paper1.21.11-132与Folia1.21.11-14各完成三次启动/两次重启，全部正常退出。最终两平台均保留六条消息、六条确认、十二条radio事件；两个契约分别COMPLETED与EXPIRED，四条已应用契约消息对应五个契约事件。去除逻辑时间字段后，最终文件内容相同；实际存档hash不同，不声称跨平台逐字节一致。Java21/25各512项测试（68个类）通过，9项Python与3项Node回归通过，完整证据见`evidence/p4/server-lifecycle/summary.json`。

这些测试不覆盖真实玩家体验、跨Folia区域负载、强制JVM崩溃、热重载、实服写盘失败注入、生产吞吐、真实资产provider或运营方信任根。512 MiB和所列JVM参数用于本机受控功能测试，不是生产配置建议。其余阶段门以`PHASE_STATUS.md`为准。
