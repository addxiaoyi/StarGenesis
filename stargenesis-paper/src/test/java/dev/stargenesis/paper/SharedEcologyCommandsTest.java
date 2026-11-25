package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SharedEcologyCommandsTest {
    private static final Pattern GROUP_ID = Pattern.compile("组 ID ([0-9a-f-]{36})");

    @TempDir Path temporaryDirectory;

    @Test
    void membersShareProgressAndRestoreWhileOwnerControlsLifecycle() throws IOException {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000401");
        UUID member = UUID.fromString("00000000-0000-0000-0000-000000000402");
        UUID guest = UUID.fromString("00000000-0000-0000-0000-000000000403");
        SharedEcologyCommands commands = new SharedEcologyCommands(temporaryDirectory);

        assertTrue(commands.execute(owner, new String[]{"list"}).contains("你还没有加入"));
        Matcher created = GROUP_ID.matcher(commands.execute(owner, new String[]{"create"}));
        assertTrue(created.find());
        String groupId = created.group(1);
        assertTrue(commands.execute(owner, new String[]{"list"}).contains("所有者"));
        assertEquals(1, commands.groupsFor(owner).size());
        assertTrue(commands.groupsFor(guest).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> commands.groupsFor(owner).clear());
        assertThrows(IllegalArgumentException.class,
                () -> commands.execute(owner, new String[]{"list", "2"}));
        assertThrows(SecurityException.class,
                () -> commands.execute(guest, new String[]{"status", groupId}));

        assertTrue(commands.execute(owner, new String[]{"add", groupId, member.toString()}).contains("2/8"));
        assertTrue(commands.execute(member, new String[]{"list"}).contains("成员"));
        assertTrue(commands.execute(member, new String[]{"status", groupId}).contains("步数=0"));
        assertTrue(commands.execute(member, new String[]{"step", groupId}).contains("第 1 步"));
        assertTrue(commands.execute(owner, new String[]{"status", groupId}).contains("步数=1"));
        assertThrows(SecurityException.class,
                () -> commands.execute(member, new String[]{"disband", groupId}));
        assertTrue(commands.execute(member, new String[]{"leave", groupId}).contains("已离开"));
        assertTrue(commands.execute(member, new String[]{"list"}).contains("你还没有加入"));

        commands.execute(owner, new String[]{"add", groupId, member.toString()});
        commands.execute(member, new String[]{"step", groupId});
        SharedEcologyCommands restored = new SharedEcologyCommands(temporaryDirectory);
        assertTrue(restored.execute(owner, new String[]{"status", groupId}).contains("步数=2"));
        assertTrue(restored.execute(member, new String[]{"status", groupId}).contains("步数=2"));
        assertTrue(restored.execute(owner, new String[]{"disband", groupId}).contains("已删除"));
        assertTrue(restored.execute(owner, new String[]{"list"}).contains("你还没有加入"));
        assertTrue(new EcologyGroupStore(temporaryDirectory).loadAll().isEmpty());
    }
}
