package dev.stargenesis.paper.lab;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LabDisplayModeTest {
    @Test void preservesPaperMenuAndFoliaCommandsByDefault() {
        assertFalse(LabDisplayMode.commands(new String[]{"start", "42", "2"}, 3, false));
        assertTrue(LabDisplayMode.commands(new String[]{"resume"}, 1, true));
    }
    @Test void explicitCommandsWorkForStartResumeAndReplayWithoutOpeningMenu() {
        assertTrue(LabDisplayMode.commands(new String[]{"start", "42", "2", "commands"}, 3, false));
        for (String action : new String[]{"resume", "replay"})
            assertTrue(LabDisplayMode.commands(new String[]{action, "commands"}, 1, false));
    }
    @Test void unknownModesExtraArgumentsAndFoliaMenuAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LabDisplayMode.commands(new String[]{"resume", "menu"}, 1, true));
        assertThrows(IllegalArgumentException.class, () -> LabDisplayMode.commands(new String[]{"resume", "unknown"}, 1, false));
        assertThrows(IllegalArgumentException.class, () -> LabDisplayMode.commands(new String[]{"replay", "commands", "extra"}, 1, false));
    }
}
