package dev.stargenesis.integrations;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only copied model metadata. Never loads models, creates instances or mutates a world.
 * The platform owns service registration/closure and enforces the provider's thread boundary.
 */
public interface ModelCatalog extends IntegrationAdapter {
    String integrationId();
    int loadedModels();
    Optional<ModelInfo> inspect(String modelId);

    record ModelInfo(String id, double scale, int bones, double hitboxWidth,
                     double hitboxHeight, List<String> animations) {
        public ModelInfo {
            id = requireId(id);
            if (!Double.isFinite(scale) || scale <= 0 || scale > 1024
                    || bones < 0 || bones > 4096
                    || !Double.isFinite(hitboxWidth) || hitboxWidth <= 0 || hitboxWidth > 1024
                    || !Double.isFinite(hitboxHeight) || hitboxHeight <= 0 || hitboxHeight > 1024)
                throw new IllegalArgumentException("Invalid model dimensions or bone budget");
            Objects.requireNonNull(animations, "animations");
            if (animations.size() > 256) throw new IllegalArgumentException("Model animation budget exceeded");
            for (String name : animations) {
                if (name == null || name.isBlank() || name.length() > 128
                        || name.chars().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7))
                    throw new IllegalArgumentException("Invalid animation name");
            }
            animations = List.copyOf(animations);
        }
    }

    static String requireId(String id) {
        if (id == null || id.length() > 128 || !id.matches("[a-z0-9_]+(/[a-z0-9_]+)?"))
            throw new IllegalArgumentException("Model ID must be a lowercase provider ID (max 128 characters)");
        return id;
    }
}
