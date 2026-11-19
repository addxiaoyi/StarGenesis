# 兼容性与证据矩阵

日期：2026-11-19。源码基线、安装、API可用、能力激活、Paper实测与Folia实测分别记录，不能合并成“已支持”。

| 对象 | 审计基线 | 源码/API事实 | StarGenesis真实功能激活 | 实际联调 |
|---|---|---|---|---|
| StarGenesis | Java21、PaperAPI1.21.11时间戳构件 | P0命令/配置/格式校验 | 仅自身基础能力 | Paper1.21.11-132与Folia1.21.11-14真实控制台生命周期通过；见evidence/p0/，无真人/世界操作证明 |
| StarCore | 1.0.0 / Java21 / Paper1.21.11，f769e627 | ServicesManager入口、可选服务；经济无外部幂等参数 | 否 | 未执行；Folia支持未证明 |
| helsteraMobs | 1.0.0 / Paper1.21.1，bb6d662c | API1.0.0、实例/行为求值主线程；Bukkit任务调度 | 否 | 1.21.11与模型客户端未执行；Folia阻断待解决 |
| CraftEngine | SenLuo声明26.9.1 | API消费证据来自SenLuo源码，非CE运行证明 | 否 | 未安装/未验证ID |
| Cookery | 1.3.0，6a432fe5 | 公开CookeryAPI；Paper1.21.11/Java21 | 否 | 未执行 |
| Doll/Tavern/Deco/Music | 0.1.0-dev，6a432fe5 | 真实插件名/目录ID已审计；声明folia-supported | 否 | 未执行，声明不等于实测 |
| WorldGuard/PlaceholderAPI/BlueMap及其他可选插件 | 尚未审计 | 不猜测API | 否 | 未执行 |

当前版本可用状态仅表示插件版本与源码审计白名单匹配，不保证二进制兼容或功能成功。任何未审计版本明确 VERSION_NOT_AUDITED；缺失可选插件可安全降级。

## 构建与实机计划

- 编译发布目标Java21，CI矩阵Java21/25；CI文件存在不表示远端workflow已执行。
- Paper1.21.11 build132：服务器SHA256 `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`。
- Folia1.21.11 build14：服务器SHA256 `f52c408490a0225611e67907a3ca19f7e6da2c6bc899e715d5f46844e7103c39`。
- `scripts/server_smoke.py` 验证真实进程的启动、五设计自检、状态/帮助/能力/预算命令、缺依赖报告与正常停服。完整结果与日志须匹配最终插件哈希。
- 此脚本没有真实玩家客户端、默认权限拒绝、模型动画、跨区域、第三方结算、GUI或多人验收；这些保留为后续阶段门。

P0 的 folia-supported 声明只覆盖当前无世界访问的插件，不传递到第三方或未来功能。后续适配器必须绑定目标server flavor/build，不能把Paper验证直接用于Folia。
