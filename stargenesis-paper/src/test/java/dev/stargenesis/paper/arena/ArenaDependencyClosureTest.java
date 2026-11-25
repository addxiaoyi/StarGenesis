package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.DesignCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaDependencyClosureTest {
    private final DesignCodec codec = new DesignCodec();
    private final Map<DesignDependency, DesignDocument> registry = new HashMap<>();

    @Test void sharedGraphIsResolvedOncePerPinAndDepthSixteenIsAccepted() throws Exception {
        List<DesignDocument> previous = List.of();
        for (int depth = 0; depth < 16; depth++) {
            var next = new ArrayList<DesignDocument>();
            for (int branch = 0; branch < 2; branch++) next.add(arena("layer-" + depth + "-" + branch, previous));
            previous = next;
        }
        var root = arena("root", previous);
        Map<DesignDependency, Integer> requests = new HashMap<>();
        ArenaDependencyClosure.validate(root, pin -> { requests.merge(pin, 1, Integer::sum); return registry.get(pin); });
        assertEquals(32, requests.size());
        assertEquals(32, requests.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void aSharedNodeReachedByALongerPathStillEnforcesTheDepthLimit() {
        DesignDocument tail = arena("leaf", List.of());
        for (int i = 0; i < 14; i++) tail = arena("tail-" + i, List.of(tail));
        var wrapper = arena("wrapper", List.of(tail));
        var wrapperAgain = arena("wrapper-again", List.of(wrapper));
        var root = arena("root", List.of(tail, wrapperAgain));
        var failure = assertThrows(IOException.class, () -> ArenaDependencyClosure.validate(root, registry::get));
        assertTrue(failure.getMessage().contains("depth"));
    }

    @Test void dependencyPermissionsAndExactPinsAreCheckedOnEveryValidation() throws Exception {
        var leaf = arena("leaf", List.of());
        var root = arena("root", List.of(leaf));
        ArenaDependencyClosure.validate(root, registry::get);
        var other = arena("other", List.of());
        assertThrows(IOException.class, () -> ArenaDependencyClosure.validate(root, ignored -> other));
        assertThrows(IOException.class, () -> ArenaDependencyClosure.validate(root, ignored -> null));
        DesignDocument denied = new SampleDocument(new DesignMetadata(leaf.metadata().id(), 1, "1.0.0", new UUID(0, 1),
                "test:denied", List.of(), leaf.metadata().hash(), new DesignPermissions(true, false, Set.of())));
        assertThrows(SecurityException.class, () -> ArenaDependencyClosure.validate(root, ignored -> denied));
    }

    @Test void transitiveNonArenaDesignsCannotHideUnresolvedOrUnlicensedDependencies() {
        var leaf = arena("leaf", List.of());
        DesignDocument bridge = new SampleDocument(new DesignMetadata(new DesignId("test:protocol"), 1, "1.0.0", new UUID(0, 1),
                "test:bridge", List.of(pin(leaf)), "1".repeat(64), new DesignPermissions(true, true, Set.of())));
        registry.put(pin(bridge), bridge);
        var root = arena("root", List.of(bridge));
        registry.remove(pin(leaf));
        assertThrows(IOException.class, () -> ArenaDependencyClosure.validate(root, registry::get));
    }

    @Test void cyclicResolverDataIsRejectedBeforeUnboundedRecursion() {
        var firstPin = new DesignDependency(new DesignId("test:cycle-a"), "1.0.0", "1".repeat(64));
        var secondPin = new DesignDependency(new DesignId("test:cycle-b"), "1.0.0", "2".repeat(64));
        var first = sample(firstPin, List.of(secondPin));
        var second = sample(secondPin, List.of(firstPin));
        registry.put(firstPin, first); registry.put(secondPin, second);
        assertThrows(IOException.class, () -> ArenaDependencyClosure.validate(first, registry::get));
    }

    private ArenaRuleset arena(String name, List<DesignDocument> children) {
        var metadata = new DesignMetadata(new DesignId("test:" + name), 1, "1.0.0", new UUID(0, 1), "test:closure",
                children.stream().map(this::pin).toList(), "", new DesignPermissions(true, true, Set.of()));
        var document = (ArenaRuleset) codec.load(codec.seal(new ArenaRuleset(metadata, 16, 16, 20, 4)));
        registry.put(pin(document), document);
        return document;
    }

    private DesignDependency pin(DesignDocument document) {
        var meta = document.metadata(); return new DesignDependency(meta.id(), meta.version(), meta.hash());
    }

    private SampleDocument sample(DesignDependency pin, List<DesignDependency> children) {
        return new SampleDocument(new DesignMetadata(pin.id(), 1, pin.version(), new UUID(0, 1), "test:graph",
                children, pin.hash(), new DesignPermissions(true, true, Set.of())));
    }

    // Resolver contract tests intentionally include non-serialized and contradictory data; production reads sealed documents.
    private record SampleDocument(DesignMetadata metadata) implements DesignDocument {
        @Override public DesignKind kind() { return DesignKind.PROTOCOL; }
    }
}
