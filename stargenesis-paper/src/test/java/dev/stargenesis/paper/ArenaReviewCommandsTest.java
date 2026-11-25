package dev.stargenesis.paper;

import dev.stargenesis.design.ArenaReviewSignature;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetEditorSession;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignExchange;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaReviewCommandsTest {
    private static final UUID REVIEWER = new UUID(0, 32);
    private static final KeyPair KEYS = DesignExchange.newSigningKey();
    private static final String READ_FAILURE = "Arena review archive could not be read; no decision state is available.";

    @Test
    void signedDecisionRemainsInspectableAfterRestartAndTrustRevocation(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaRuleset candidate = fixture.candidate(1);
        fixture.queue.submitSigned(candidate, candidate.metadata().author(), "a".repeat(64));
        String hash = candidate.metadata().hash();
        String signature = fixture.signature(candidate, ArenaReviewSignature.Outcome.APPROVE, "");
        fixture.queue.approve(hash, REVIEWER, signature);
        fixture.trust.revoke(REVIEWER);

        ArenaReviewQueue restarted = new ArenaReviewQueue(directory, fixture.codec, fixture.releases, fixture.trust);
        ArenaReviewQueue.DecisionInspection inspection = restarted.inspectDecision(hash).orElseThrow();
        assertEquals(ArenaReviewQueue.PublicationState.PUBLISHED, inspection.publication());
        assertEquals(candidate.metadata().author(), inspection.decision().submitter());
        assertEquals(REVIEWER, inspection.decision().reviewer());
        assertEquals("a".repeat(64), inspection.decision().keyFingerprint());
        assertEquals(DesignExchange.fingerprint(KEYS.getPublic().getEncoded()), inspection.decision().reviewerFingerprint());
        assertEquals(signature, inspection.decision().reviewerSignatureBase64());
        assertArrayEquals(KEYS.getPublic().getEncoded(), Base64.getDecoder().decode(inspection.decision().reviewerPublicKeyBase64()));

        Map<String, String> before = snapshot(directory);
        CapturedSender output = new CapturedSender(true);
        new ArenaReviewCommands(restarted).execute(output.sender, inspect(hash));
        assertTrue(output.text().contains("publication=PUBLISHED"));
        assertTrue(output.text().contains("signature=Ed25519-valid"));
        assertTrue(output.text().contains("signatureBase64=" + signature));
        assertEquals(before, snapshot(directory), "Queries must neither change decisions nor restore revoked trust");
    }

    @Test
    void historyIsOrderedPaginatedAndNeverRevealsOtherPages(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        for (int index = 1; index <= 21; index++) fixture.reject(fixture.candidate(index), "Adjust the layout.");
        var first = fixture.queue.listDecisions(1);
        var last = fixture.queue.listDecisions(2);
        assertEquals(21, first.totalRecords());
        assertEquals(2, first.totalPages());
        assertEquals(20, first.decisions().size());
        assertEquals(1, last.decisions().size());
        List<ArenaReviewQueue.DecisionView> all = new ArrayList<>(first.decisions());
        all.addAll(last.decisions());
        assertEquals(21, all.stream().map(ArenaReviewQueue.DecisionView::hash).distinct().count());
        assertEquals(all.stream().sorted(Comparator.comparing(ArenaReviewQueue.DecisionView::hash)).toList(), all);
        assertEquals(first, fixture.queue.listDecisions(1));

        Map<String, String> before = snapshot(directory);
        ArenaReviewCommands commands = new ArenaReviewCommands(fixture.queue);
        CapturedSender output = new CapturedSender(true);
        commands.execute(output.sender, new String[]{"arena", "decisions"});
        assertEquals(21, output.messages.size());
        assertTrue(output.text().contains("records=21 | page=1/2"));
        assertFalse(output.text().contains(last.decisions().getFirst().hash()));
        output.clear();
        commands.execute(output.sender, new String[]{"arena", "decisions", "2"});
        assertEquals(2, output.messages.size());
        assertTrue(output.text().contains(last.decisions().getFirst().hash()));
        assertEquals(before, snapshot(directory));

        Path offPage = directory.resolve("arena-reviews/decisions")
                .resolve(last.decisions().getFirst().hash() + ".decision");
        byte[] damaged = Files.readAllBytes(offPage);
        damaged[damaged.length - 1] ^= 1;
        Files.write(offPage, damaged);
        assertEquals(first, fixture.queue.listDecisions(1), "Only the requested page's decision bodies are read");
        assertThrows(IOException.class, () -> fixture.queue.listDecisions(2));
    }

    @Test
    void queriesDistinguishDurableApprovalFromPublicationWithoutRecoveringIt(@TempDir Path directory) throws Exception {
        Fixture reference = new Fixture(directory.resolve("reference"));
        ArenaRuleset candidate = reference.candidate(1);
        reference.queue.submit(candidate, candidate.metadata().author());
        reference.queue.approve(candidate.metadata().hash(), REVIEWER,
                reference.signature(candidate, ArenaReviewSignature.Outcome.APPROVE, ""));

        Fixture recovering = new Fixture(directory.resolve("recovering"));
        recovering.queue.submit(candidate, candidate.metadata().author());
        Path decision = recovering.decisionPath(candidate);
        Files.createDirectories(decision.getParent());
        Files.copy(reference.decisionPath(candidate), decision);
        Map<String, String> before = snapshot(recovering.directory);
        CapturedSender output = new CapturedSender(true);
        new ArenaReviewCommands(recovering.queue).execute(output.sender, inspect(candidate.metadata().hash()));

        assertTrue(output.text().contains("publication=APPROVED_AWAITING_PUBLICATION"));
        assertTrue(recovering.releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
        assertEquals(1, recovering.queue.listPending().size());
        assertEquals(before, snapshot(recovering.directory));
    }

    @Test
    void corruptionAfterSuccessfulQueryNeverReturnsCachedOrPartialHistory(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaRuleset candidate = fixture.candidate(1);
        fixture.reject(candidate, "Adjust the layout.");
        ArenaReviewCommands commands = new ArenaReviewCommands(fixture.queue);
        CapturedSender output = new CapturedSender(true);
        commands.execute(output.sender, inspect(candidate.metadata().hash()));
        assertTrue(output.text().contains("publication=NOT_PUBLISHED"));
        byte[] encoded = Files.readAllBytes(fixture.decisionPath(candidate));
        encoded[encoded.length - 1] ^= 1;
        Files.write(fixture.decisionPath(candidate), encoded);

        assertUnreadable(commands, output, candidate.metadata().hash());
    }

    @Test
    void checksumAloneCannotAuthenticateATamperedSignature(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaRuleset candidate = fixture.candidate(1);
        fixture.reject(candidate, "Adjust the layout.");
        byte[] encoded = Files.readAllBytes(fixture.decisionPath(candidate));
        int payloadLength = encoded.length - 32;
        encoded[payloadLength - 1] ^= 1;
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(encoded, payloadLength));
        System.arraycopy(checksum, 0, encoded, payloadLength, checksum.length);
        Files.write(fixture.decisionPath(candidate), encoded);

        assertUnreadable(new ArenaReviewCommands(fixture.queue), new CapturedSender(true), candidate.metadata().hash());
    }

    @Test
    void archiveReadBudgetsAndFilenamesFailClosed(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaRuleset candidate = fixture.candidate(1);
        fixture.reject(candidate, "Adjust the layout.");
        byte[] valid = Files.readAllBytes(fixture.decisionPath(candidate));
        Files.write(fixture.decisionPath(candidate), new byte[2049]);
        assertThrows(IOException.class, () -> fixture.queue.inspectDecision(candidate.metadata().hash()));
        assertThrows(IOException.class, () -> fixture.queue.listDecisions(1));
        Files.write(fixture.decisionPath(candidate), valid);
        Path malformed = fixture.decisionPath(candidate).resolveSibling("malformed.decision");
        Files.write(malformed, valid);
        assertThrows(IOException.class, () -> fixture.queue.listDecisions(1));
    }

    @Test
    void permissionGateAndProtectedRoutingApplyBeforeArchiveAccess(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        Files.writeString(directory.resolve("arena-reviews"), "Archive deliberately unavailable");
        CapturedSender output = new CapturedSender(false);
        ArenaReviewCommands commands = new ArenaReviewCommands(fixture.queue);
        for (String[] args : List.of(new String[]{"arena", "decisions"}, inspect("a".repeat(64)))) {
            output.clear();
            commands.execute(output.sender, args);
            assertEquals(List.of(Component.text("StarGenesis: permission denied (stargenesis.arena.review)")), output.messages);
        }
        for (String operation : List.of("review", "approve", "reject", "decisions", "decision", "recover")) {
            assertTrue(ArenaReviewCommands.requiresReviewPermission(operation));
            assertTrue(ArenaReviewCommands.requiresReviewPermission(operation.toUpperCase(java.util.Locale.ROOT)));
        }
        assertTrue(ArenaReviewCommands.isQuery(new String[]{"arena", "DECISIONS"}));
        assertFalse(ArenaReviewCommands.requiresReviewPermission("rooms"));
        assertFalse(ArenaReviewCommands.isQuery(new String[]{"arena"}));
    }

    @Test
    void emptyUnknownAndInvalidQueriesHaveBoundedResponses(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaReviewCommands commands = new ArenaReviewCommands(fixture.queue);
        CapturedSender output = new CapturedSender(true);
        commands.execute(output.sender, new String[]{"arena", "decisions"});
        assertEquals(List.of(Component.text("Arena review decisions | records=0 | page=1/1")), output.messages);
        for (String page : List.of("0", "2", "-1", "1.0", "01", "9999999999999", "411")) {
            output.clear();
            commands.execute(output.sender, new String[]{"arena", "decisions", page});
            assertEquals(1, output.messages.size());
            assertFalse(output.text().contains("records="));
        }
        output.clear();
        commands.execute(output.sender, inspect("a".repeat(64)));
        assertEquals(List.of(Component.text("Unknown arena review decision.")), output.messages);
        for (String hash : List.of("../decisions", "a".repeat(63), "A".repeat(64))) {
            output.clear();
            commands.execute(output.sender, inspect(hash));
            assertEquals(List.of(Component.text("Review ID must be a full lowercase SHA-256 hash")), output.messages);
        }
        output.clear();
        commands.execute(output.sender, new String[]{"arena", "decision"});
        assertTrue(output.text().startsWith("Usage:"));
    }

    @Test
    void reasonsAreLiteralComponentsWithExactMultilingualContent(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaRuleset candidate = fixture.candidate(1);
        String reason = "<click:run_command:'/op nobody'>地图需要调整🌍</click>";
        fixture.reject(candidate, reason);
        CapturedSender output = new CapturedSender(true);
        new ArenaReviewCommands(fixture.queue).execute(output.sender, inspect(candidate.metadata().hash()));

        assertTrue(output.messages.contains(Component.text("reason=" + reason)));
        output.messages.forEach(component -> {
            assertInstanceOf(TextComponent.class, component);
            assertTrue(component.children().isEmpty());
            assertNull(component.clickEvent());
        });
    }

    @Test
    void rejectionOfAnAlreadyReleasedHashDoesNotClaimToUnpublishIt(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaRuleset candidate = fixture.candidate(1);
        fixture.releases.publish(candidate, candidate.metadata().author());
        fixture.reject(candidate, "This decision does not revoke the existing release.");
        CapturedSender output = new CapturedSender(true);
        new ArenaReviewCommands(fixture.queue).execute(output.sender, inspect(candidate.metadata().hash()));

        assertTrue(output.text().contains(" | REJECT | "));
        assertTrue(output.text().contains("publication=PUBLISHED"));
        assertTrue(fixture.releases.findArenaByHash(candidate.metadata().hash()).isPresent());
    }

    @Test
    void consoleCanExplicitlyRecoverAnApprovedReleaseWithoutTheReviewerOnline(@TempDir Path directory) throws Exception {
        Fixture reference = new Fixture(directory.resolve("reference"));
        Fixture recovering = new Fixture(directory.resolve("recovering"));
        ArenaRuleset candidate = recovering.interruptedApprovalFrom(reference);
        byte[] decision = Files.readAllBytes(recovering.decisionPath(candidate));
        recovering.trust.revoke(REVIEWER);
        ArenaReviewQueue restarted = new ArenaReviewQueue(recovering.directory, recovering.codec,
                recovering.releases, recovering.trust);
        ArenaReviewRecoveryCommands commands = new ArenaReviewRecoveryCommands(restarted);
        CapturedSender output = new CapturedSender(true);
        String signature = recovering.signature(candidate, ArenaReviewSignature.Outcome.APPROVE, "");

        commands.execute(output.sender, recover(candidate.metadata().hash(), signature));

        assertEquals(1, output.messages.size());
        assertTrue(output.text().contains("Recovered approved arena release "));
        assertTrue(output.text().contains("reviewer=" + REVIEWER));
        assertEquals(candidate, recovering.releases.findArenaByHash(candidate.metadata().hash()).orElseThrow());
        assertTrue(restarted.listPending().isEmpty());
        assertTrue(recovering.trust.publicKey(REVIEWER).isEmpty());
        assertArrayEquals(decision, Files.readAllBytes(recovering.decisionPath(candidate)));
        Map<String, String> published = snapshot(recovering.directory);
        output.clear();
        commands.execute(output.sender, recover(candidate.metadata().hash(), signature));
        assertTrue(output.text().startsWith("Recovered approved arena release "));
        assertEquals(published, snapshot(recovering.directory));
        assertFalse(ArenaReviewCommands.isQuery(recover(candidate.metadata().hash(), signature)));
    }

    @Test
    void consoleRecoveryNeverCreatesAnApprovalOrChangesARejection(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        ArenaRuleset candidate = fixture.candidate(1);
        fixture.queue.submit(candidate, candidate.metadata().author());
        ArenaReviewRecoveryCommands commands = new ArenaReviewRecoveryCommands(fixture.queue);
        CapturedSender output = new CapturedSender(true);
        String signature = fixture.signature(candidate, ArenaReviewSignature.Outcome.APPROVE, "");
        Map<String, String> before = snapshot(directory);
        commands.execute(output.sender, recover(candidate.metadata().hash(), signature));
        assertTrue(output.text().contains("No persisted arena approval exists"));
        assertEquals(before, snapshot(directory));

        fixture.reject(candidate, "Approval is not granted.");
        Map<String, String> rejected = snapshot(directory);
        output.clear();
        commands.execute(output.sender, recover(candidate.metadata().hash(), signature));
        assertTrue(output.text().contains("No persisted arena approval exists"));
        assertEquals(rejected, snapshot(directory));
        assertTrue(fixture.releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
    }

    @Test
    void recoveryPermissionIsCheckedBeforeReadingAnUnavailableArchive(@TempDir Path directory) throws Exception {
        Fixture fixture = new Fixture(directory);
        Files.writeString(directory.resolve("arena-reviews"), "Archive deliberately unavailable");
        CapturedSender output = new CapturedSender(false);
        new ArenaReviewRecoveryCommands(fixture.queue).execute(output.sender, recover("a".repeat(64), "invalid"));
        assertEquals(List.of(Component.text("StarGenesis: permission denied (stargenesis.arena.review)")), output.messages);
        assertTrue(ArenaReviewCommands.requiresReviewPermission("RECOVER"));
        assertEquals("Archive deliberately unavailable", Files.readString(directory.resolve("arena-reviews")));
    }

    @Test
    void malformedRecoveryArgumentsNeverMutateTheApprovedCandidate(@TempDir Path directory) throws Exception {
        Fixture reference = new Fixture(directory.resolve("reference"));
        Fixture fixture = new Fixture(directory.resolve("recovering"));
        ArenaRuleset candidate = fixture.interruptedApprovalFrom(reference);
        String hash = candidate.metadata().hash();
        String signature = fixture.signature(candidate, ArenaReviewSignature.Outcome.APPROVE, "");
        ArenaReviewRecoveryCommands commands = new ArenaReviewRecoveryCommands(fixture.queue);
        CapturedSender output = new CapturedSender(true);
        Map<String, String> before = snapshot(fixture.directory);
        for (String[] args : List.of(new String[]{}, new String[]{"arena", "recover"},
                new String[]{"arena", "recover", hash, "1-1-1-1-1", signature},
                new String[]{"arena", "recover", hash, "not-a-uuid", signature},
                new String[]{"arena", "recover", hash, new UUID(0, 99).toString(), signature},
                recover("../outside", signature), recover(hash, "invalid"),
                new String[]{"arena", "recover", hash, REVIEWER.toString(), signature, "extra"})) {
            output.clear();
            commands.execute(output.sender, args);
            assertEquals(1, output.messages.size());
            assertFalse(output.text().contains("Recovered approved arena release"));
            assertEquals(before, snapshot(fixture.directory));
        }
    }

    @Test
    void recoveryCannotUseATamperedDecisionEvenAfterItsChecksumIsRecomputed(@TempDir Path directory) throws Exception {
        Fixture reference = new Fixture(directory.resolve("reference"));
        Fixture fixture = new Fixture(directory.resolve("recovering"));
        ArenaRuleset candidate = fixture.interruptedApprovalFrom(reference);
        byte[] record = Files.readAllBytes(fixture.decisionPath(candidate));
        int payloadLength = record.length - 32;
        record[payloadLength - 1] ^= 1;
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(record, payloadLength));
        System.arraycopy(checksum, 0, record, payloadLength, checksum.length);
        Files.write(fixture.decisionPath(candidate), record);
        Map<String, String> before = snapshot(fixture.directory);
        CapturedSender output = new CapturedSender(true);
        String signature = fixture.signature(candidate, ArenaReviewSignature.Outcome.APPROVE, "");

        new ArenaReviewRecoveryCommands(fixture.queue).execute(output.sender, recover(candidate.metadata().hash(), signature));

        assertTrue(output.text().contains("Arena approval recovery failed:"));
        assertFalse(output.text().contains("Recovered approved arena release"));
        assertEquals(before, snapshot(fixture.directory));
        assertTrue(fixture.releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
    }

    private static String[] recover(String hash, String signature) {
        return new String[]{"arena", "recover", hash, REVIEWER.toString(), signature};
    }

    private static void assertUnreadable(ArenaReviewCommands commands, CapturedSender output, String hash) {
        for (String[] args : List.of(new String[]{"arena", "decisions"}, inspect(hash))) {
            output.clear();
            commands.execute(output.sender, args);
            assertEquals(List.of(Component.text(READ_FAILURE)), output.messages);
        }
    }

    private static String[] inspect(String hash) { return new String[]{"arena", "decision", hash}; }

    private static Map<String, String> snapshot(Path directory) throws Exception {
        Map<String, String> snapshot = new TreeMap<>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                snapshot.put(directory.relativize(file).toString(), HexFormat.of().formatHex(Files.readAllBytes(file)));
        }
        return snapshot;
    }

    private static final class Fixture {
        private final Path directory;
        private final DesignCodec codec = new DesignCodec();
        private final ArenaRuleset starter;
        private final PublishedDesignStore releases;
        private final ArenaReviewTrustFileStore trust;
        private final ArenaReviewQueue queue;

        private Fixture(Path directory) throws Exception {
            this.directory = directory;
            try (var input = DesignCodec.class.getResourceAsStream("/examples/arena_ruleset-v2.json")) {
                assertNotNull(input);
                starter = (ArenaRuleset) codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            releases = new PublishedDesignStore(directory.resolve("published-designs"), codec);
            releases.seedBundled(starter);
            trust = new ArenaReviewTrustFileStore(directory);
            trust.trust(REVIEWER, KEYS.getPublic().getEncoded());
            queue = new ArenaReviewQueue(directory, codec, releases, trust);
        }

        private ArenaRuleset candidate(int index) {
            var editor = ArenaRulesetEditorSession.create(new UUID(0, 100 + index), starter);
            editor.preview();
            return (ArenaRuleset) codec.load(editor.save(codec));
        }

        private void reject(ArenaRuleset candidate, String reason) throws Exception {
            queue.submit(candidate, candidate.metadata().author());
            queue.reject(candidate.metadata().hash(), REVIEWER, reason,
                    signature(candidate, ArenaReviewSignature.Outcome.REJECT, reason));
        }

        private String signature(ArenaRuleset candidate, ArenaReviewSignature.Outcome outcome, String reason) {
            return Base64.getEncoder().encodeToString(ArenaReviewSignature.sign(candidate.metadata().hash(),
                    REVIEWER, outcome, reason, KEYS));
        }

        private ArenaRuleset interruptedApprovalFrom(Fixture reference) throws Exception {
            ArenaRuleset candidate = reference.candidate(1);
            String fingerprint = "a".repeat(64);
            reference.queue.submitSigned(candidate, candidate.metadata().author(), fingerprint);
            reference.queue.approve(candidate.metadata().hash(), REVIEWER,
                    signature(candidate, ArenaReviewSignature.Outcome.APPROVE, ""));
            queue.submitSigned(candidate, candidate.metadata().author(), fingerprint);
            Files.createDirectories(decisionPath(candidate).getParent());
            Files.copy(reference.decisionPath(candidate), decisionPath(candidate));
            return candidate;
        }

        private Path decisionPath(ArenaRuleset candidate) {
            return directory.resolve("arena-reviews/decisions").resolve(candidate.metadata().hash() + ".decision");
        }
    }

    private static final class CapturedSender {
        private final List<Component> messages = new ArrayList<>();
        private final CommandSender sender;

        private CapturedSender(boolean permitted) {
            sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                    new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                        if (method.getName().equals("hasPermission") && args[0].equals("stargenesis.arena.review"))
                            return permitted;
                        if (method.getName().equals("sendMessage") && args.length == 1 && args[0] instanceof Component component) {
                            messages.add(component);
                            return null;
                        }
                        if (method.getName().equals("toString")) return "CapturedArenaReviewer";
                        throw new AssertionError("Unexpected command sender operation: " + method);
                    });
        }

        private String text() {
            return String.join("\n", messages.stream().map(component -> ((TextComponent) component).content()).toList());
        }

        private void clear() { messages.clear(); }
    }
}
