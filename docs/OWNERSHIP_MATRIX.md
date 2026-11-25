# 权威状态与线程所有权

| 状态/行为 | 唯一权威 | StarGenesis 可做 | 禁止/边界 |
|---|---|---|---|
| 设计、程序、协议、实验规则、来源/版本 | StarGenesis design | 校验、保存、授权复用、显式迁移 | 不执行任意用户代码；哈希不等于作者授权 |
| 模拟位置、资源、能源、耐久、得分 | StarGenesis sim 单一工作线程 | 接收排序输入、整数计量、事件/回放 | 不从 Bukkit 显示反推；不兑付真实资产 |
| 实验/竞技生命周期、群体、UGC、专属统计 | StarGenesis | 配额、隔离、证据、回放 | 不获得国家/经济权威 |
| P7 竞技草稿与审核 | StarGenesis review queue / immutable release store | 作者提交；具审核权限的操作员批准或拒绝；以发布 hash 固定规则 | 不能修改审核前版本；SHA-256 不证明审核者身份 |
| P8 多人竞技房间 | StarGenesis sim；Paper/Folia 全局调度器串行拥有房间 | UUID 席位、锁步动作、超时等待、只读观战、服务端有界原子检查点 | 不跨服同步，不访问真实世界或资产；不提供反作弊 |
| P9 真实交易协调记录 | StarGenesis settlement saga journal；未来第三方 adapter 持有真实余额与库存 | 固定交易 ID、逐步幂等键、状态查询、逆序补偿、持久恢复 | 当前不调用第三方钱包/背包 API，不启用真实结算；本地日志不协调多服务器 |
| 国家、成员、科技、战争、区块申领 | StarCore 真实服务 | 经过验证的只读快照/明确授权请求 | 不重写、不直接读数据库；成员身份非动作许可 |
| 真实玩家/国库余额 | StarCore 经济服务 | 未来受验证 saga 结算 | 模拟分不得deposit；无幂等接口不得盲重试 |
| 物品库存、方块与保护 | Bukkit/保护插件/实际库存 | 所属区域调度、验证后一次转移 | 不按自定义ID凭空重造；不绕过领地权限 |
| P1 实验室检查点 | StarGenesis sim；Paper Player PersistentDataContainer 保存编码值 | 玩家自己的界面显示模拟网格并保存检查点 | 不写玩家物品槽，不将模拟资源兑付为真实物品 |
| P2 蓝图装配进度 | StarGenesis design；Paper Player PersistentDataContainer 保存有界进度检查点 | 按已保存蓝图展示私人 3D 坐标、方向、模块和连接装配练习 | 不生成 Minecraft 方块，不消耗真实物品，不授予生存建造或地块权限 |
| 模型、动画、实体实例 | helsteraMobs | 模拟AgentId到临时显示实例的投影 | 显示非真相；实例主线程限制不得绕过 |
| 真实自定义物品与生产内容 | SenLuo/CraftEngine | 运行时ID验证、保留PDC的适配 | 不把series内部类当稳定SDK；不替代配方消耗 |

## 并发和生命周期

不可变 StepInput/StepResult/DesignDocument 是线程间交换边界；sim 时钟/账本/outbox 实例由构造线程拥有，越线程访问失败。Bukkit/Paper/第三方类型不进入公共API和纯模拟。

Paper 世界访问在合法宿主线程执行；Folia 按实体/区域/全局的实际所有权封送。跨区不得同步读取另一地区的实体或背包。上游所谓“主线程”、锁或 `isMainThread=true` 都不是区域所有权证据。

关闭顺序：停止接收输入→停止/排空受控工作→保留恢复证据→在所属调度器清理投影→关闭适配器/注销监听→释放持久化。P0 没有模拟工作、投影和结算任务；后续需逐项实现并实测。

EvidenceId 为 `sg:v1:world:tick:sequence`。模拟账本去重历史不能被删后重用；真实交易/租约/订单还需独立业务唯一ID与持久状态机。内存outbox不提供崩溃恢复保证。
