package dev.stargenesis.api.design;
import java.util.Set;
import java.util.UUID;
public record DesignPermissions(boolean publicRead, boolean allowClone, Set<UUID> editors) {
    public DesignPermissions {
        editors = Set.copyOf(editors);
        if (editors.size() > 64) throw new IllegalArgumentException("Too many editors");
    }
    public boolean mayEdit(UUID actor, UUID author) { return author.equals(actor) || editors.contains(actor); }
}
