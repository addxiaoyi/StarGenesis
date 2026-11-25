package dev.stargenesis.design;

import java.util.List;
import java.util.Objects;

public record BlueprintPreview(Blueprint candidate, BlueprintAnalysis before, BlueprintAnalysis after,
                               BlueprintShapeDifference difference, List<String> issues) {
    public BlueprintPreview {
        Objects.requireNonNull(candidate, "candidate"); Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after"); Objects.requireNonNull(difference, "difference");
        issues = List.copyOf(issues);
    }
    public boolean canSave() { return issues.isEmpty() && before.valid() && after.valid(); }
}
