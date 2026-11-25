package dev.stargenesis.paper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Command rules for the pre-factory organization roster. */
final class FactoryOrganizationSession {
    private final FactoryOrganization organization;

    private FactoryOrganizationSession(FactoryOrganization organization) {
        this.organization = Objects.requireNonNull(organization, "organization");
    }

    static CommandResult create(UUID owner, String name, boolean factoryStarted) {
        if (factoryStarted) throw new IllegalStateException("The factory roster is already frozen");
        if (owner == null) throw new SecurityException("An organization must be created by a player");
        return new CommandResult(new FactoryOrganizationSession(
                new FactoryOrganization(name, owner, List.of(owner))),
                "组织 " + name + " 已创建，你是所有者。使用 /genesis factory org add <uuid> 邀请成员。", true);
    }

    static FactoryOrganizationSession restore(FactoryOrganization organization) {
        return new FactoryOrganizationSession(organization);
    }

    FactoryOrganization organization() {
        return organization;
    }

    List<UUID> factoryMembers(UUID actor) {
        requireOwner(actor);
        if (organization.members().size() != FactoryOrganization.MAX_MEMBERS) {
            throw new IllegalStateException("组织需要四名成员才能创建工厂。");
        }
        return organization.members();
    }

    CommandResult command(UUID actor, String[] arguments, boolean factoryStarted) {
        if (arguments.length == 0) return unchanged(usage());
        return switch (arguments[0].toLowerCase(java.util.Locale.ROOT)) {
            case "status" -> {
                requireLength(arguments, 1, "Usage: /genesis factory org status");
                yield unchanged(status(factoryStarted));
            }
            case "add" -> addMember(actor, arguments, factoryStarted);
            case "remove" -> removeMember(actor, arguments, factoryStarted);
            default -> unchanged(usage());
        };
    }

    private CommandResult addMember(UUID actor, String[] arguments, boolean factoryStarted) {
        requireLength(arguments, 2, "Usage: /genesis factory org add <uuid>");
        requireEditable(actor, factoryStarted);
        FactoryOrganization updated = organization.addMember(parseUuid(arguments[1]));
        return new CommandResult(new FactoryOrganizationSession(updated),
                "已将 " + arguments[1] + " 加入组织 " + organization.name() + "。", true);
    }

    private CommandResult removeMember(UUID actor, String[] arguments, boolean factoryStarted) {
        requireLength(arguments, 2, "Usage: /genesis factory org remove <uuid>");
        requireEditable(actor, factoryStarted);
        FactoryOrganization updated = organization.removeMember(parseUuid(arguments[1]));
        return new CommandResult(new FactoryOrganizationSession(updated),
                "已将 " + arguments[1] + " 从组织 " + organization.name() + " 移除。", true);
    }

    private String status(boolean factoryStarted) {
        StringBuilder message = new StringBuilder("组织 ").append(organization.name())
                .append(factoryStarted ? " | 名册已冻结" : " | 开厂前名册")
                .append(" | 成员 ").append(organization.members().size()).append('/')
                .append(FactoryOrganization.MAX_MEMBERS)
                .append("\n所有者 ").append(organization.owner());
        for (UUID member : organization.members()) message.append("\n成员 ").append(member);
        return message.toString();
    }

    private void requireEditable(UUID actor, boolean factoryStarted) {
        requireOwner(actor);
        if (factoryStarted) throw new IllegalStateException("工厂已创建，组织名册不能再修改。");
    }

    private void requireOwner(UUID actor) {
        if (actor == null || !organization.owner().equals(actor)) {
            throw new SecurityException("只有组织所有者可以执行此操作。");
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("成员必须使用完整 UUID。");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("成员必须使用完整 UUID。", failure);
        }
    }

    private static void requireLength(String[] arguments, int expected, String usage) {
        if (arguments.length != expected) throw new IllegalArgumentException(usage);
    }

    private static String usage() {
        return "组织命令：org status | org create <name> | org add <uuid> | org remove <uuid>";
    }

    private CommandResult unchanged(String message) {
        return new CommandResult(this, message, false);
    }

    record CommandResult(FactoryOrganizationSession session, String message, boolean changed) {
        CommandResult {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(message, "message");
        }
    }
}
