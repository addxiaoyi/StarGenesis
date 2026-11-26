# Paper/Folia 重启续跑测试

`scripts/server_lifecycle_smoke.py`在同一个隔离目录顺序启动三次实际服务端。四个固定离线客户端使用相同名字和UUID，命令通过真实玩家连接进入插件。测试不会重置已有存档；目录必须没有此前的生命周期测试结果和服务存档。

预先安装Node.js 22或更高版本和支持Minecraft 1.21.11的Mineflayer，并在隔离目录提供已接受的`eula.txt`。测试只绑定`127.0.0.1`，不启用在线认证或真实资产结算。示例：

```sh
python scripts/server_lifecycle_smoke.py \
  --kind paper --java /path/to/jdk/bin/java \
  --server-jar /path/to/paper-1.21.11-132.jar \
  --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar \
  --node-modules /path/to/node_modules \
  --directory .work/lifecycle-paper --port 25671 --heap-mb 1024
```

Folia使用`--kind folia`和对应官方JAR。可用`--libraries-from`复用已提取服务端的依赖，或在隔离目录准备bootstrap所需的官方缓存。`--heap-mb`允许256–8192；低于512 MiB仅用于资源受限的启动诊断，不保证服务端可以启动，也不代表推荐生产配置。两个平台顺序运行。测试服务端设8个登录名额，实际只连接4个客户端；Folia 1.21.11-14在4名额配置下曾拒绝第四个登录。新隔离目录的Folia线程配置写入实际读取的`config/paper-global.yml`，设置两个线程；已有该文件时保留配置。

可重复传入`--jvm-arg=-XX:...`，参数作为独立进程参数传递并写入结果。资源受限的Windows复测使用`-XX:+UseSerialGC`、`-XX:TieredStopAtLevel=1`、`-XX:ReservedCodeCacheSize=64m`和`-XX:ActiveProcessorCount=2`，降低编译器及GC的原生内存开销。此前两次JVM原生内存分配失败保留为失败记录；原目录恢复后六份已提交文件保持原字节、四项回放通过。该偶发崩溃发生在只读回放/启动期间，不代表已覆盖写盘中途强制崩溃。

## 三次启动

1. `prepare`：以seed42创建1536步生态v2，推进至768步并核对三物种、物质总量和回放；创建四人工厂，保存将触发确定性补偿的待完成订单；创建Arena并封存一名玩家的动作。最后启动四造物实验室，在客户端确认RUNNING时发出服务器stop。Paper保持实验菜单打开。
2. `resume`：启动后和四玩家重新加入后，逐文件核对六份服务存档的完整SHA-256。检查四人UUID、工厂场次和Arena场次及pending动作自动恢复。生态续跑至1536步；工厂订单推进后核对失败补偿、退款和escrow释放；Arena撤回恢复的动作、重新提交并完成四人回合。验证选择、比赛中禁止退出及普通成员禁止清理命令。实验室先回放检查点，再恢复原场次继续运行，并再次随服务器关闭保存。
3. `verify`：再次核对所有存档字节、身份和场次，回放生态、工厂、Arena与实验室。只读操作以及最终关闭后，所有服务存档应保持第二次关闭时的原字节。

每次服务器退出必须成功，日志必须确认插件正常关闭，四个`.worker.lock`必须可由独立宿主重新加锁。检查点的tick必须覆盖关服前客户端观察到的tick，并与完整history及末帧hash一致；第二次实验室保存必须延长同一worldId的原历史。

`lifecycle-evidence/<phase>/`保留原始控制台、客户端结果、所有存档原字节及SHA-256清单。总结果记录运行时、服务端/插件哈希、检查点和证据文件哈希。发生失败也保留已生成证据，不把失败结果转写为通过。

## 验证边界

这是实际服务器的正常关闭与重启恢复证据。固定四客户端、单个seed、两次重启不构成真人验收、广泛生态平衡、生产容量、不同Folia区域、强制JVM崩溃、插件热重载或第三方provider兼容性证据。工作线程超时/忽略中断的目录隔离由独立单元与跨进程探针覆盖，不能用本流程代替。

CI无需下载Minecraft或接受EULA，只运行证据检查器回归：拒绝丢失/篡改/新增文件、缺失锁文件、滞后或不完整检查点、恢复时更换场次或改写历史。实服流程需要显式提供上述运行环境。
