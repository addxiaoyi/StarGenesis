# 发明文档契约（P0 / schema 1）

本模块提供五类不可变 Java 文档、离线 JSON Schema、严格 JSON 编解码和显式迁移。P0 仅定义和校验数据，不执行 Program、通信、真实交易或竞技规则。模块没有 Bukkit、第三方游戏插件、反射和玩家脚本执行依赖。

## 公开入口

- `DesignCodec.load(String)`：接收已封存文档，验证版本、大小、SHA-256 和完整模型后返回 `DesignDocument`。
- `DesignCodec.seal(String)`：只允许 `metadata.hash` 为空的新文档；校验后产生确定的哈希，返回可持久化 JSON。此操作不是作者授权验证，调用方必须另外执行权限检查。
- `DesignCodec.write(DesignDocument)`：输出现有不可变文档；哈希过期时拒绝，不能用此方法悄悄重新封存篡改文档。
- `DesignCodec.migrateV0(String)`：仅支持显式 `schemaVersion=0` 的 `metadata.revision` → schema 1 的 `metadata.version`；验证旧哈希，返回旧/新哈希、迁移 JSON 和新文档。未知版本、隐式迁移、重复迁移均拒绝。

五个公开 record 是 `Blueprint`、`Program`、`Protocol`、`Contract` 和 `ArenaRuleset`；公共元数据在 `stargenesis-api`。对应资源：`/schemas/{kind_lower}.schema.json` 与 `/examples/{kind_lower}.json`。`ARENA_RULESET` 文件名为 `arena_ruleset`。

## 版本、哈希和身份

所有文档外壳都包含 `kind` 和 `metadata`。元数据包括 `{value:"namespace:path"}` 格式的稳定 ID、`schemaVersion`、严格 `x.y.z` 设计版本、规范小写 UUID 作者、来源、锁定版本和哈希的依赖，以及公共读取、克隆和编辑者权限。依赖 ID 不可重复，编辑者集合不可重复。路径穿越式 ID 被拒绝。

哈希是完整文档删除 `metadata.hash` 后的 SHA-256：递归按键的 Java 字符序排序，编辑者 UUID 集合按字典序排序，其他数组保持声明顺序，再由 Jackson 输出无空白 UTF-8 JSON。字符串不进行 Unicode 规范化，未配对 UTF-16 代理字符拒绝。只支持整数数据；没有浮点舍入问题。此规范是 StarGenesis schema 1 的专用规范化格式，**不是 RFC 8785/JCS，也不是数字签名**。哈希证明完整性，不能证明作者身份或许可有效性；P9/P10 导入、交易和发布还需受信任的授权/签名流程。

## 安全边界

单文档最多 262,144 UTF-8 字节，嵌套最多 32 层，单字符串解析上限 4096 个 UTF-16 单元，数字最多 10 个字符。模型字段再施加更小的明确上限。拒绝重复 JSON 键、未知字段、缺失字段、null 原始值、尾随 JSON、枚举数字、整数/浮点/布尔值转字符串，以及字符串转数值/布尔值。

Schema 覆盖字段、类型、枚举、格式和数量边界；Java 模型继续验证无法由简洁 JSON Schema 表达的语义：结构重叠/端点/连通性、唯一组件/节点/协议字段、图边类型与无环约束、Contract 的协议依赖锁定，以及实际内容哈希。发布和导入必须调用 `DesignCodec.load`，不能只检查 JSON Schema。来源和参数的 Java 长度约束按 UTF-16 单元计算，因此对于包含补充平面字符的输入可能比 JSON Schema 的码点长度更严格。

P0 Blueprint 只表示连接结构，不宣称有 P2 组件物理；Program 当前是批准枚举的无环类型图，不是 P3 可执行 IR；Protocol/Contract 只是有界交换格式；ArenaRuleset 只是有界容器，不宣称已完成 P7 规则/路径/胜负检查。任何新字段或语义变更必须提供版本兼容方案和测试。

## 验证

从仓库根执行 `mvn -B -pl stargenesis-design -am verify`，或使用仓库 Maven Wrapper。测试使用 JUnit 和仅测试范围的 NetworkNT JSON Schema 校验器，实际加载五份 Schema，校验五个公开示例，并验证非法输入、类型强制转换、篡改、显式迁移、图约束和不可变性。测试不会启动 Paper，也不能代表游戏内编辑器、多人或第三方兼容性验收。
