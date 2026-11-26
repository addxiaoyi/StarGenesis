package dev.stargenesis.paper.communication;

import dev.stargenesis.design.Protocol;

import java.util.Map;
import java.util.UUID;

/** Parses the virtual radio send command without touching player or persistent state. */
record RadioSendCommand(Protocol protocol, UUID recipient, int sequence, Map<String, String> payload) {
    RadioSendCommand { payload = Map.copyOf(payload); }

    static RadioSendCommand parse(String[] args) {
        boolean radioMessage = args.length == 5;
        if (!radioMessage && args.length != 7)
            throw new IllegalArgumentException("用法：/genesis radio send <recipient> <sequence> <channel> <value> | send contract <recipient> <sequence> <contract> <action> <units>");
        int offset = radioMessage ? 0 : 1;
        String protocolId = radioMessage ? RadioProtocol.ID.value() : args[1];
        if (protocolId.equals("contract")) protocolId = ServiceContractDefinition.PROTOCOL_ID.value();
        Protocol protocol;
        if (protocolId.equals(RadioProtocol.ID.value())) protocol = RadioProtocol.protocol();
        else if (protocolId.equals(ServiceContractDefinition.PROTOCOL_ID.value())) protocol = ServiceContractDefinition.protocol();
        else throw new IllegalArgumentException("未知或未安装 protocol " + protocolId);
        UUID recipient = UUID.fromString(args[1 + offset]);
        if (!recipient.toString().equals(args[1 + offset])) throw new IllegalArgumentException("UUID 必须为规范小写格式");
        int sequence = Integer.parseInt(args[2 + offset]);
        Map<String, String> payload;
        if (radioMessage) {
            payload = Map.of("sequence", Integer.toString(sequence), "channel", args[3], "value", args[4]);
        } else {
            if (!protocol.metadata().id().equals(ServiceContractDefinition.PROTOCOL_ID))
                throw new IllegalArgumentException("该发送格式用于 genesis:service 协议");
            payload = Map.of("contract", args[4], "action", args[5], "units", args[6]);
        }
        return new RadioSendCommand(protocol, recipient, sequence, payload);
    }
}
