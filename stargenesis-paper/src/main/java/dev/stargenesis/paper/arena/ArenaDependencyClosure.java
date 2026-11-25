package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignKind;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Validates each pinned document once, including shared subgraphs, while preserving the longest-path limit. */
final class ArenaDependencyClosure {
    static final int MAX_DEPTH = 16;
    private static final int MAX_DESIGNS = ArenaRulesetStore.MAX_PUBLISHED_ARENAS + DesignKind.values().length + 1;
    private final Function<DesignDependency, DesignDocument> resolver;
    private final Map<DesignDependency, DesignDocument> resolved = new HashMap<>();
    private final Map<DesignDependency, Integer> heights = new HashMap<>();
    private final Set<DesignDependency> visiting = new HashSet<>();

    private ArenaDependencyClosure(Function<DesignDependency, DesignDocument> resolver) { this.resolver = resolver; }

    static void validate(DesignDocument root, Function<DesignDependency, DesignDocument> resolver) throws IOException {
        new ArenaDependencyClosure(resolver).visit(root, 0);
    }

    private int visit(DesignDocument document, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Arena dependency depth exceeds its budget");
        DesignDependency key = pin(document);
        Integer cached = heights.get(key);
        if (cached != null) {
            if (depth + cached > MAX_DEPTH) throw new IOException("Arena dependency depth exceeds its budget");
            return cached;
        }
        if (!visiting.add(key)) throw new IOException("Arena dependency cycle rejected");
        if (heights.size() + visiting.size() > MAX_DESIGNS)
            throw new IOException("Arena dependency closure exceeds its document budget");
        int height = 0;
        for (DesignDependency dependency : document.metadata().dependencies()) {
            DesignDocument target = resolved.get(dependency);
            if (target == null) {
                target = resolver.apply(dependency);
                if (target == null) throw new IOException("Unresolved design dependency " + dependency.id().value());
                if (!pin(target).equals(dependency)) throw new IOException("Resolved dependency identity or hash differs from its pin");
                if (!target.metadata().permissions().publicRead() || !target.metadata().permissions().allowClone())
                    throw new SecurityException("Dependency does not grant publicRead and allowClone: " + dependency.id().value());
                resolved.put(dependency, target);
            }
            height = Math.max(height, 1 + visit(target, depth + 1));
        }
        visiting.remove(key);
        heights.put(key, height);
        return height;
    }

    private DesignDependency pin(DesignDocument document) {
        var metadata = document.metadata();
        return new DesignDependency(metadata.id(), metadata.version(), metadata.hash());
    }
}
