package dev.stargenesis.paper.communication;

import dev.stargenesis.design.ProtocolMessageCodec;

import java.util.List;
import java.util.UUID;

/** A legacy acknowledgement is valid only when its shorter identity selects one pending message. */
record RadioAcknowledgement(UUID sender, long tick, int sequence, VirtualMessageBus.ProtocolKey protocol) {
    RadioAcknowledgement {
        if (sender == null || tick < 0 || sequence < 0)
            throw new IllegalArgumentException("确认消息需要有效发送者、非负 tick 和 sequence");
        if (protocol != null && !protocol.equals(RadioProtocol.key()) && !protocol.equals(ServiceContractDefinition.key()))
            throw new IllegalArgumentException("未知或未安装 protocol");
    }

    static RadioAcknowledgement parse(String[] args) {
        if (args.length != 4 && args.length != 5)
            throw new IllegalArgumentException("用法：/genesis radio ack <sender UUID> <message tick> <sequence> [genesis:radio|genesis:service]");
        UUID sender = UUID.fromString(args[1]);
        if (!sender.toString().equals(args[1])) throw new IllegalArgumentException("UUID 必须为规范小写格式");
        VirtualMessageBus.ProtocolKey protocol = args.length == 4 ? null : switch (args[4]) {
            case "genesis:radio" -> RadioProtocol.key();
            case "genesis:service", "contract" -> ServiceContractDefinition.key();
            default -> throw new IllegalArgumentException("未知或未安装 protocol " + args[4]);
        };
        return new RadioAcknowledgement(sender, Long.parseLong(args[2]), Integer.parseInt(args[3]), protocol);
    }

    ProtocolMessageCodec.Message select(List<ProtocolMessageCodec.Message> pending) {
        var matches = pending.stream().filter(message -> message.sender().equals(sender) && message.tick() == tick
                && message.sequence() == sequence && (protocol == null
                || message.protocol().equals(protocol.id()) && message.protocolHash().equals(protocol.hash()))).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("找不到已投递的消息，请先 inbox");
        if (matches.size() != 1)
            throw new IllegalArgumentException("多个协议的消息具有相同发送者、tick 和 sequence；请在 ack 末尾指定 genesis:radio 或 genesis:service");
        return matches.getFirst();
    }
}
