# P4 消息确认身份

消息幂等身份包含协议ID、协议hash、发送者UUID和sequence；tick用于标识这次实际发送。不同协议可以合法复用发送者、tick和sequence。旧控制器只按后三项选择第一条待投递消息，存在歧义时仍会持久确认其中一条。先机械提取原选择逻辑后，一条回归复现了这个问题；不是实服事故记录。

`/genesis radio ack <sender UUID> <message tick> <sequence>` 保持兼容，但必须在收件人当前已安装协议的已投递消息中唯一匹配。多个协议同时匹配时，拒绝并提示使用完整选择：

```
/genesis radio ack <sender UUID> <message tick> <sequence> genesis:radio
/genesis radio ack <sender UUID> <message tick> <sequence> genesis:service
```

`contract` 是 `genesis:service` 的别名。服务会同时核对所选协议ID及其固定hash，未知协议、非法UUID、负tick/sequence、未送达消息、其他收件人的消息以及当前未安装协议均不能被选中。不同tick或唯一匹配仍可使用短格式。

解析与选择集中在不依赖Bukkit的 `RadioAcknowledgement`。选择与原子保存都在 `RadioService` 同一个同步范围内；候选保存失败保留原内存状态和文件，允许再次确认相同消息。成功确认后该消息不会再次进入待投递列表，重复命令不追加历史事件。

`ack` 只确认投递，不执行契约动作。有效契约消息通过 `inbox` 应用契约状态并同时确认；人工明确确认无效契约消息，可以删除这条待投递记录，仍保留其幂等与确认证据。此规则与旧格式一致，持久schema、协议文档与hash没有变化。

无效契约消息的 `inbox` 提示会同时提供完整确认命令，包含原始发送者UUID、发送tick、sequence及固定协议ID，并明确说明该操作丢弃投递、不执行契约。仅显示提示不会自动确认，消息继续保留到玩家主动操作。

新增9项回归覆盖两种发送顺序、同tick跨协议歧义、显式radio/contract选择、无效契约清理、重启、真实临时文件写入失败、当前协议权限、其他收件人及不合法参数。针对性45项通信测试已通过。该修复属于RC4之后的SNAPSHOT，不改写已验证的RC4工件；没有为本批增加实服或真人验收结论。

确认身份修复提交`6ad8b3b`在Java21/25各完整`clean verify`通过548项测试（72个测试类），失败/错误/跳过均为0；两个JDK的目录锁、结算锁和临时密钥签名自检也通过。完整证据见`evidence/p4/acknowledgement-identity/summary.json`，包括27份原始工件与9份源码hash。后续完整确认命令提示另由既有45项通信回归验证，原证据保留对应提交的源码hash。
