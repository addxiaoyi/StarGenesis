package dev.stargenesis.integrations;

import java.util.Set;

/** Evidence supplied only by trusted server adapter code; never loaded from player designs. */
public record AdapterVerification(boolean apiVerified, boolean paperVerified,
                                  Set<String> activeCapabilities, String evidenceId) {
    public AdapterVerification {
        activeCapabilities = Set.copyOf(activeCapabilities);
        evidenceId = InstalledPlugin.requireText(evidenceId, "evidenceId");
        if (paperVerified && !apiVerified) {
            throw new IllegalArgumentException("Paper evidence requires verified API");
        }
        if (!activeCapabilities.isEmpty() && (!apiVerified || !paperVerified)) {
            throw new IllegalArgumentException("Activation requires API and Paper evidence");
        }
    }
}
