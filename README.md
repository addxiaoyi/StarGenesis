# StarGenesis · 创世沙盒

**玩家发明规则，世界检验发明。**

原创 Minecraft 创作与确定性模拟插件。长期目标是让玩家组合结构、有限行为图、通信协议、组织和规则创作新玩法。当前为 **4.0.0-SNAPSHOT / P0 基础验收通过、P1 开发中**，尚未交付可验收的实验或竞技玩法。

实际阶段状态见 [docs/PHASE_STATUS.md](docs/PHASE_STATUS.md)，产品/线程/资源契约见 [docs/PRODUCT_CONTRACT.md](docs/PRODUCT_CONTRACT.md)。

## 构建

需要 Java 21+ 和网络；Maven Wrapper 固定 Maven 3.9.11 并校验分发包 SHA-256。

```sh
./mvnw -B verify
python scripts/check_boundaries.py
```

Windows 使用 `mvnw.cmd -B verify`。产物：`stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar`；安装此 shaded JAR，不要安装 `original-*` 或 api/sim 等独立库 JAR。Paper API 固定 `1.21.11-R0.1-20260511.115010-91`；未把 Bukkit/Paper 或第三方插件打入发布物。

## 安装与操作

复制插件 JAR 到 Paper 1.21.11 的 plugins，启动服务器。首次生成 config.yml；插件启动时校验预算、配置版本和五份内置设计文档。未知配置或 `integrations.allow-real-settlement: true` 会拒绝启动。P0 没有真实库存/货币转移，也没有后台模拟任务。

| 命令 | 权限 | 默认 |
|---|---|---|
| `/genesis help` | stargenesis.help | 所有人 |
| `/genesis status` | stargenesis.status | 所有人 |
| `/genesis debug capabilities` | stargenesis.debug | OP |
| `/genesis debug budgets` | stargenesis.debug | OP |

能力报告分别显示插件安装/启用、审计版本、API验证、Paper证据及逐项能力。安装插件不会自动激活未验证桥接；报告是启动时快照，配置/依赖更改后重启。StarCore、helsteraMobs 与 SenLuo/CraftEngine 的具体限制见 [docs/INTEGRATION_AUDIT.md](docs/INTEGRATION_AUDIT.md)。

Folia 支持声明仅针对当前没有世界访问的 P0 插件；不代表 helsteraMobs 或未来跨区域功能通过验证。

## 真实服务器测试

提供独立目录，避免接触生产世界；脚本绑定 localhost，生成最小测试世界，执行命令并停服，输出原始控制台日志、机器可读结果和工件哈希。

```sh
python scripts/server_smoke.py --server-jar /path/paper.jar --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --directory .work/server-paper --accept-eula
python scripts/server_smoke.py --server-jar /path/folia.jar --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar --directory .work/server-folia --kind folia --accept-eula
```

`--accept-eula` 仅在接受 Mojang EULA 时使用；也可预先提供已接受的 eula.txt。可用 `--java /path/java` 指定运行时。测试只证明真实服务器控制台生命周期，不证明真人客户端、模型、跨区域操作或第三方经济兼容。

## 工程结构与后续开发

五个有实现模块：api（稳定契约）、design（格式/安全校验/迁移）、sim（时钟/守恒账本/outbox）、integrations（能力发现合同）、paper（插件宿主）。JSON Schema 和签名哈希示例在 design 模块资源目录；SHA-256 为完整性校验，不是作者签名。

每次变更执行正式 verify，新增公共接口须有合同测试。阶段门、缺口和实际证据持续记入 docs；后续实现不得把模拟数量兑付真实物品，不得用 Mock 冒充实机验收。
