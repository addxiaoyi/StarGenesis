# 权威状态与线程所有权

| 状态/行为 | 唯一权威 | StarGenesis 可做 | 禁止/边界 |
|---|---|---|---|
| 设计、程序、协议、实验规则、来源/版本 | StarGenesis design | 校验、保存、授权复用、显式迁移 | 不执行任意用户代码；哈希不等于作者授权 |
| 模拟位置、资源、能源、耐久、得分 | StarGenesis sim 单一工作线程 | 接收排序输入、整数计量、事件/回放 | 不从 Bukkit 显示反推；不兑付真实资产 |
| 协议 radio、封闭生态、模拟组织/工厂与竞技、群体、UGC、专属统计 | StarGenesis simulation adapters | 守恒 matter、有限进化、模拟拍卖/订单/补偿、territory round resolution、有界消息、规则 hash pin、证据与回放 | 模拟 credits/ore/parts/unit 不是真实库存或经济；observer 投影只读；不获得国家权威 |
| 国家、成员、科技、战争、区块申领 | StarCore 真实服务 | 经过验证的只读快照/明确授权请求 | 不重写、不直接读数据库；成员身份非动作许可 |
| 真实玩家/国库余额 | StarCore 经济服务 | 仅在持久 saga + 外部幂等/查询 API + 权限 evidence 全部验收后启用 | 当前 StarCore 转账无外部幂等键；settlement adapter inactive；模拟分不得 deposit |
| 物品库存、方块与保护 | Bukkit/保护插件/实际库存 | 所属区域调度、验证后一次转移 | 不按自定义ID凭空重造；不绕过领地权限 |
| 模型、动画、实体实例 | helsteraMobs | 模拟AgentId到临时显示实例的投影 | 显示非真相；实例主线程限制不得绕过 |
| 真实自定义物品与生产内容 | SenLuo/CraftEngine | 运行时ID验证、保留PDC的适配 | 不把series内部类当稳定SDK；不替代配方消耗 |

## 并发和生命周期

不可变 StepInput/StepResult/DesignDocument 是线程间交换边界；sim 时钟/账本/outbox 实例由构造线程拥有，越线程访问失败。Bukkit/Paper/第三方类型不进入公共API和纯模拟。

Paper 世界访问在合法宿主线程执行；Folia 按实体/区域/全局的实际所有权封送。跨区不得同步读取另一地区的实体或背包。上游所谓“主线程”、锁或 `isMainThread=true` 都不是区域所有权证据。

关闭顺序：停止接收输入→停止/排空受控工作→保留恢复证据→在所属调度器清理投影→关闭适配器/注销监听→释放持久化。P0 没有模拟工作、投影和结算任务；后续需逐项实现并实测。

EvidenceId 为 `sg:v1:world:tick:sequence`。模拟账本去重历史不能被删后重用；真实交易/租约/订单还需独立业务唯一ID与持久状态机。内存outbox不提供崩溃恢复保证。
