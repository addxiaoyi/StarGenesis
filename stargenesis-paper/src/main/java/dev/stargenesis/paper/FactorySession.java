package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.ResourceLedger.TransferResult;
import dev.stargenesis.sim.FactoryRecipe;
import dev.stargenesis.sim.FactoryCheckpoint;
import dev.stargenesis.sim.FactoryCheckpointCodec;
import dev.stargenesis.sim.FactorySettings;
import dev.stargenesis.sim.FactoryWorld;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transactional command surface over a checkpointed virtual factory. */
final class FactorySession {
    private static final int MAX_ADVANCE_PER_COMMAND = 100;

    private final FactoryCheckpoint checkpoint;

    private FactorySession(FactoryCheckpoint checkpoint) {
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
    }

    static FactorySession start(List<UUID> members) {
        List<AgentId> agents = Objects.requireNonNull(members, "members").stream()
                .map(AgentId::new).toList();
        FactorySettings settings = new FactorySettings("factory-main", agents, 72_000, 24, 1_000,
                FactorySettings.MAX_ORDERS);
        try (FactoryWorld world = new FactoryWorld(settings)) {
            FactoryCheckpoint checkpoint = world.checkpoint();
            FactoryCheckpointCodec.encode(checkpoint);
            return new FactorySession(checkpoint);
        }
    }

    static FactorySession restore(FactoryCheckpoint checkpoint) {
        FactoryCheckpointCodec.encode(checkpoint);
        return new FactorySession(checkpoint);
    }

    FactoryCheckpoint checkpoint() {
        return checkpoint;
    }

    byte[] encode() {
        return FactoryCheckpointCodec.encode(checkpoint);
    }

    boolean containsMember(UUID playerId) {
        return playerId != null && checkpoint.settings().members().contains(new AgentId(playerId));
    }

    CommandResult command(UUID actorId, String[] arguments) {
        if (arguments.length == 0) return unchanged(usage());
        return switch (arguments[0].toLowerCase(java.util.Locale.ROOT)) {
            case "status" -> unchanged(status());
            case "orders" -> orders(arguments);
            case "order" -> createOrder(actorId, arguments);
            case "bid" -> placeBid(actorId, arguments);
            case "contribute" -> contribute(actorId, arguments);
            case "fulfill" -> fulfill(actorId, arguments);
            case "advance" -> advance(actorId, arguments);
            case "station" -> station(actorId, arguments);
            default -> unchanged(usage());
        };
    }

    private CommandResult station(UUID actorId, String[] arguments) {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("Usage: /genesis factory station <status|queue|cancel|collect> ...");
        }
        AgentId actor = requireMember(actorId);
        return switch (arguments[1].toLowerCase(java.util.Locale.ROOT)) {
            case "status" -> {
                requireLength(arguments, 2, "Usage: /genesis factory station status");
                yield unchanged(stationStatus(actor));
            }
            case "queue" -> queueProduction(actor, arguments);
            case "cancel" -> cancelProduction(actor, arguments);
            case "collect" -> collectProduction(actor, arguments);
            default -> unchanged("Usage: /genesis factory station <status|queue|cancel|collect> ...");
        };
    }

    private CommandResult queueProduction(AgentId actor, String[] arguments) {
        requireLength(arguments, 5,
                "Usage: /genesis factory station queue <recipe-id> <action-sequence> <batches>");
        DesignId recipeId = new DesignId(arguments[2]);
        long actionSequence = parseLong(arguments[3], "action-sequence");
        int batches = parseInt(arguments[4], "batches");
        return mutate(world -> {
            FactoryWorld.ProductionActionResult result = world.queueProduction(actor, actionSequence,
                    recipeId, batches);
            return switch (result) {
                case QUEUED -> {
                    FactoryWorld.StationJob job = world.workstation(actor).job();
                    yield "生产任务已排入 " + recipeId.value() + "，预计 tick " + job.completesAt() + " 完成。";
                }
                case STATION_BUSY -> "工位正在运行任务，请等待完成或先取消。";
                case INSUFFICIENT_MATERIALS -> "原料不足，未排入生产任务。";
                case CANCELLED, NO_JOB -> throw new IllegalStateException("Unexpected factory queue result");
            };
        });
    }

    private CommandResult cancelProduction(AgentId actor, String[] arguments) {
        requireLength(arguments, 3,
                "Usage: /genesis factory station cancel <action-sequence>");
        long actionSequence = parseLong(arguments[2], "action-sequence");
        return mutate(world -> switch (world.cancelProduction(actor, actionSequence)) {
            case CANCELLED -> "生产任务已取消，原料已退回成员库存。";
            case NO_JOB -> "当前工位没有运行中的任务。";
            case QUEUED, STATION_BUSY, INSUFFICIENT_MATERIALS ->
                    throw new IllegalStateException("Unexpected factory cancel result");
        });
    }

    private CommandResult collectProduction(AgentId actor, String[] arguments) {
        requireLength(arguments, 4,
                "Usage: /genesis factory station collect <action-sequence> <amount>");
        long actionSequence = parseLong(arguments[2], "action-sequence");
        long amount = parseLong(arguments[3], "amount");
        return mutate(world -> {
            TransferResult result = world.collectProduction(actor, actionSequence, amount);
            return switch (result) {
                case APPLIED -> "已领取 " + amount + " 个组件到成员虚拟库存。";
                case ALREADY_APPLIED -> "该领取已登记，没有重复转入组件。";
                case INSUFFICIENT_FUNDS -> "工位组件不足，本次失败尝试已记录。";
            };
        });
    }

    private String stationStatus(AgentId actor) {
        try (FactoryWorld world = FactoryWorld.restore(checkpoint)) {
            FactoryWorld.WorkstationSnapshot workstation = world.workstation(actor);
            StringBuilder message = new StringBuilder("虚拟工位 | tick ").append(world.tick())
                    .append(" | 原料：成员库存矿石 ")
                    .append(world.balance(FactoryWorld.playerAccount(actor), FactoryWorld.ORE_RESOURCE))
                    .append("、聚合物 ")
                    .append(world.balance(FactoryWorld.playerAccount(actor), FactoryWorld.POLYMER_RESOURCE))
                    .append(" | 工位矿石 ").append(workstation.ore())
                    .append("、聚合物 ").append(workstation.polymer())
                    .append("、组件 ").append(workstation.components());
            if (workstation.job() == null) message.append("\n当前没有运行任务。");
            else message.append("\n运行中 ").append(workstation.job().recipeId().value())
                    .append(" | 批次 ").append(workstation.job().batches())
                    .append(" | 完成 tick ").append(workstation.job().completesAt());
            message.append("\n配方 ");
            for (int index = 0; index < FactoryWorld.recipes().size(); index++) {
                FactoryRecipe recipe = FactoryWorld.recipes().get(index);
                if (index > 0) message.append("、");
                message.append(recipe.id().value()).append(" ").append(recipe.ticksPerBatch())
                        .append(" tick/批，最多 ").append(recipe.maxBatches()).append(" 批");
            }
            return message.toString();
        }
    }

    private CommandResult createOrder(UUID actorId, String[] arguments) {
        requireLength(arguments, 6, "Usage: /genesis factory order <id> <components> <reward> <auction-ticks> <delivery-ticks>");
        AgentId actor = requireMember(actorId);
        DesignId orderId = new DesignId(arguments[1]);
        long components = parseLong(arguments[2], "components");
        long reward = parseLong(arguments[3], "reward");
        int auctionTicks = parseInt(arguments[4], "auction-ticks");
        int deliveryTicks = parseInt(arguments[5], "delivery-ticks");
        return mutate(world -> world.createOrder(actor, orderId, components, reward, auctionTicks, deliveryTicks),
                "订单已创建 " + orderId.value() + "，奖励已托管。\n" +
                        "成员使用 /genesis factory bid " + orderId.value() + " <action-sequence> <price> 竞价。");
    }

    private CommandResult placeBid(UUID actorId, String[] arguments) {
        requireLength(arguments, 4, "Usage: /genesis factory bid <id> <action-sequence> <price>");
        AgentId actor = requireMember(actorId);
        DesignId orderId = new DesignId(arguments[1]);
        long actionSequence = parseLong(arguments[2], "action-sequence");
        long price = parseLong(arguments[3], "price");
        return mutate(world -> {
            FactoryWorld.Order order = world.placeBid(actor, orderId, actionSequence, price);
            return "竞价已记录 " + order.id().value() + "，当前竞标数 " + order.bids().size() + "/"
                    + FactorySettings.MEMBER_COUNT + "，状态 " + order.status() + ".";
        });
    }

    private CommandResult contribute(UUID actorId, String[] arguments) {
        requireLength(arguments, 4, "Usage: /genesis factory contribute <id> <action-sequence> <amount>");
        AgentId actor = requireMember(actorId);
        DesignId orderId = new DesignId(arguments[1]);
        long actionSequence = parseLong(arguments[2], "action-sequence");
        long amount = parseLong(arguments[3], "amount");
        return mutate(world -> {
            TransferResult result = world.contribute(actor, orderId, actionSequence, amount);
            return switch (result) {
                case APPLIED -> "已向订单 " + orderId.value() + " 贡献 " + amount + " 个虚拟组件。";
                case ALREADY_APPLIED -> "该贡献已登记，未重复扣除组件。";
                case INSUFFICIENT_FUNDS -> "虚拟组件不足，本次失败尝试已记录。";
            };
        });
    }

    private CommandResult fulfill(UUID actorId, String[] arguments) {
        requireLength(arguments, 2, "Usage: /genesis factory fulfill <id>");
        AgentId actor = requireMember(actorId);
        DesignId orderId = new DesignId(arguments[1]);
        return mutate(world -> world.fulfill(actor, orderId)
                ? "订单 " + orderId.value() + " 已交付，虚拟奖励已结算。"
                : "订单组件仍不足，交付未执行。");
    }

    private CommandResult advance(UUID actorId, String[] arguments) {
        requireLength(arguments, 3, "Usage: /genesis factory advance <expected-tick> <1-100>");
        requireMember(actorId);
        long expectedTick = parseLong(arguments[1], "expected-tick");
        if (expectedTick < 0 || expectedTick != checkpoint.tick()) {
            throw new IllegalStateException("工厂 tick 已变化，请先查看 status 后再推进。");
        }
        int steps = parseInt(arguments[2], "steps");
        if (steps < 1 || steps > MAX_ADVANCE_PER_COMMAND) {
            throw new IllegalArgumentException("每次只能推进 1 到 " + MAX_ADVANCE_PER_COMMAND + " 个逻辑步。");
        }
        return mutate(world -> {
            for (int index = 0; index < steps; index++) world.step();
            return "工厂已推进 " + steps + " 步，当前 tick " + world.tick() + "。";
        });
    }

    private String status() {
        try (FactoryWorld world = FactoryWorld.restore(checkpoint)) {
            StringBuilder message = new StringBuilder("虚拟工厂 factory-main | tick ")
                    .append(checkpoint.tick()).append('/').append(checkpoint.settings().maxSteps())
                    .append(" | 订单 ").append(checkpoint.orders().size()).append('/')
                    .append(checkpoint.settings().maxOrders()).append('\n')
                    .append("状态哈希 ").append(world.snapshot().stateHash(), 0, 16).append('\n')
                    .append("四人固定名册与虚拟余额");
            for (AgentId member : checkpoint.settings().members()) {
                message.append("\n").append(member.value()).append(" | 组件 ")
                        .append(world.balance(FactoryWorld.playerAccount(member), FactoryWorld.COMPONENT_RESOURCE))
                        .append(" | 矿石 ")
                        .append(world.balance(FactoryWorld.playerAccount(member), FactoryWorld.ORE_RESOURCE))
                        .append(" | 聚合物 ")
                        .append(world.balance(FactoryWorld.playerAccount(member), FactoryWorld.POLYMER_RESOURCE))
                        .append(" | 信用 ")
                        .append(world.balance(FactoryWorld.playerAccount(member), FactoryWorld.CREDIT_RESOURCE));
            }
            message.append("\n真实物品和经济未启用。");
            return message.toString();
        }
    }

    private CommandResult orders(String[] arguments) {
        if (arguments.length > 2)
            throw new IllegalArgumentException("Usage: /genesis factory orders [page]");
        int page = arguments.length == 1 ? 1 : parseInt(arguments[1], "page");
        return unchanged(ordersPage(page));
    }

    private String ordersPage(int page) {
        if (checkpoint.orders().isEmpty()) return "当前没有工厂订单。";
        int pageSize = 16;
        int pageCount = (checkpoint.orders().size() + pageSize - 1) / pageSize;
        if (page < 1 || page > pageCount)
            throw new IllegalArgumentException("订单页码必须在 1 到 " + pageCount + " 之间。");
        int start = (page - 1) * pageSize;
        StringBuilder message = new StringBuilder("工厂订单 | 第 ").append(page).append('/').append(pageCount).append(" 页");
        for (int index = start; index < Math.min(start + pageSize, checkpoint.orders().size()); index++) {
            FactoryWorld.Order order = checkpoint.orders().get(index);
            message.append("\n").append(order.id().value()).append(" | ").append(order.status())
                    .append(" | 组件 ").append(order.requiredComponents())
                    .append(" | 奖励 ").append(order.rewardCredits())
                    .append(" | 竞价 ").append(order.bids().size()).append('/')
                    .append(FactorySettings.MEMBER_COUNT);
            if (order.winner() != null) message.append(" | 中标者 ").append(order.winner().value());
        }
        return message.toString();
    }

    private CommandResult mutate(WorldAction action, String message) {
        return mutate(world -> {
            action.apply(world);
            return message;
        });
    }

    private CommandResult mutate(WorldMessageAction action) {
        try (FactoryWorld world = FactoryWorld.restore(checkpoint)) {
            String message = action.apply(world);
            FactoryCheckpoint staged = world.checkpoint();
            FactoryCheckpointCodec.encode(staged);
            boolean changed = !checkpoint.equals(staged);
            return new CommandResult(changed ? new FactorySession(staged) : this, message, changed);
        }
    }

    private AgentId requireMember(UUID actorId) {
        if (actorId == null) throw new SecurityException("该工厂操作需要成员玩家身份。");
        AgentId actor = new AgentId(actorId);
        if (!checkpoint.settings().members().contains(actor)) {
            throw new SecurityException("你不属于此工厂的四人名册。");
        }
        return actor;
    }

    private static long parseLong(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + " 必须是整数。", failure);
        }
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + " 必须是整数。", failure);
        }
    }

    private static void requireLength(String[] arguments, int expected, String usage) {
        if (arguments.length != expected) throw new IllegalArgumentException(usage);
    }

    private static String usage() {
        return "工厂命令：status | orders | order <id> <components> <reward> <auction-ticks> <delivery-ticks>"
                + " | bid <id> <action-sequence> <price> | contribute <id> <action-sequence> <amount>"
                + " | fulfill <id> | advance <expected-tick> <1-100>"
                + " | station <status|queue|cancel|collect> ...";
    }

    private CommandResult unchanged(String message) {
        return new CommandResult(this, message, false);
    }

    record CommandResult(FactorySession session, String message, boolean changed) {
        CommandResult {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(message, "message");
        }
    }

    @FunctionalInterface
    private interface WorldAction {
        void apply(FactoryWorld world);
    }

    @FunctionalInterface
    private interface WorldMessageAction {
        String apply(FactoryWorld world);
    }
}
