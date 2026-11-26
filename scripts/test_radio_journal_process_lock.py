"""Check P4 radio lock exclusion and stale-writer journal preservation across bounded JVMs."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


PROBE_SOURCE = r'''package dev.stargenesis.paper.communication;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RadioJournalProcessProbe {
    private static final UUID ALICE = new UUID(0, 81), BOB = new UUID(0, 82);
    private static final Set<VirtualMessageBus.ProtocolKey> INSTALLED = Set.of(
            RadioProtocol.key(), ServiceContractDefinition.key());
    private static final VirtualMessageBus.Endpoint ENDPOINT =
            new VirtualMessageBus.Endpoint("process_probe", 0, 0, 0, 16);

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        Path file = root.resolve("radio-state.json");
        switch (args[0]) {
            case "seed" -> store(file).save(new VirtualMessageBus(0, 8), runtime(0));
            case "hold" -> {
                try (var channel = FileChannel.open(root.resolve("radio-state.json.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var ignored = channel.lock()) {
                    Files.writeString(root.resolve("holding"), "holding");
                    waitFor(root.resolve("release"));
                }
            }
            case "reject" -> {
                var persistence = store(file);
                expectIo("lock", persistence::load);
                expectIo("lock", () -> persistence.save(new VirtualMessageBus(0, 8), runtime(0)));
                Files.writeString(root.resolve("rejected"), "load and save rejected while locked");
            }
            case "acquire" -> {
                var persistence = store(file);
                var saved = persistence.load().orElseThrow();
                persistence.save(restoredBus(saved), restoredContracts(saved));
                Files.writeString(root.resolve("acquired"), "load and save succeeded");
            }
            case "stale" -> {
                var persistence = store(file);
                var saved = persistence.load().orElseThrow();
                var bus = restoredBus(saved);
                var contracts = restoredContracts(saved);
                Files.writeString(root.resolve("loaded"), "old snapshot loaded");
                waitFor(root.resolve("attempt-stale-save"));
                expectIo("stale", () -> persistence.save(bus, contracts));
                Files.writeString(root.resolve("stale-rejected"), "stale save rejected");
            }
            case "rotate" -> {
                try (var service = new RadioService(store(file), 8)) {
                    service.join(ALICE, ENDPOINT, INSTALLED);
                    service.join(BOB, ENDPOINT, INSTALLED);
                    if (!service.send(ALICE, RadioProtocol.protocol(), BOB, 7, payload("original")).queued())
                        throw new AssertionError("New radio message was not queued");
                    service.advance();
                    var message = service.poll(BOB, 1).getFirst();
                    if (!service.acknowledge(BOB, message) || service.rotate() != 1)
                        throw new AssertionError("Acknowledgement or rotation failed");
                }
                Files.writeString(root.resolve("rotated"), "one committed archive");
            }
            case "verify" -> {
                try (var service = new RadioService(store(file), 8)) {
                    if (service.archiveCount() != 1 || service.retainedEvidenceCount() != 0
                            || service.participantCount() != 0 || service.tick() != 1)
                        throw new AssertionError("Committed journal did not recover");
                    if (service.send(ALICE, RadioProtocol.protocol(), BOB, 7, payload("original")).queued())
                        throw new AssertionError("Archived request was delivered again");
                    boolean conflict = false;
                    try { service.send(ALICE, RadioProtocol.protocol(), BOB, 7, payload("changed")); }
                    catch (IllegalArgumentException expected) { conflict = true; }
                    if (!conflict) throw new AssertionError("Archived request conflict was accepted");
                }
            }
            default -> throw new IllegalArgumentException("Unknown probe mode");
        }
        System.out.println("PASS " + args[0]);
    }

    private static RadioPersistence store(Path file) throws IOException {
        return new RadioPersistence(file, RadioProtocol.protocol(), ServiceContractDefinition.protocol(),
                ServiceContractDefinition.contract());
    }

    private static ContractRuntime runtime(long tick) {
        return new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                tick, ContractRuntime.MAX_RETAINED_CONTRACTS);
    }

    private static VirtualMessageBus restoredBus(RadioPersistence.Snapshot saved) {
        var bus = new VirtualMessageBus(saved.radio().tick(), 8);
        bus.restore(saved.radio(), Map.of(RadioProtocol.key(), RadioProtocol.protocol(),
                ServiceContractDefinition.key(), ServiceContractDefinition.protocol()));
        return bus;
    }

    private static ContractRuntime restoredContracts(RadioPersistence.Snapshot saved) {
        return ContractRuntime.restore(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                ContractRuntime.MAX_RETAINED_CONTRACTS, saved.contracts());
    }

    private static Map<String, String> payload(String value) {
        return Map.of("sequence", "7", "channel", "probe", "value", value);
    }

    private static void waitFor(Path signal) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!Files.exists(signal)) {
            if (System.nanoTime() >= deadline) throw new IOException("Timed out waiting for " + signal.getFileName());
            Thread.sleep(20);
        }
    }

    private static void expectIo(String reason, IoAction action) throws Exception {
        try { action.run(); }
        catch (IOException expected) {
            if (expected.getMessage() == null || !expected.getMessage().toLowerCase(java.util.Locale.ROOT).contains(reason))
                throw new AssertionError("Unexpected rejection reason", expected);
            return;
        }
        throw new AssertionError("Expected " + reason + " rejection");
    }

    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
'''


def run_probe(command: list[str], mode: str, root: Path) -> subprocess.CompletedProcess:
    result = subprocess.run(command + [mode, str(root)], capture_output=True, text=True,
                            encoding='utf-8', errors='replace', timeout=20,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    if result.returncode or f'PASS {mode}' not in result.stdout:
        raise AssertionError(f'{mode} failed ({result.returncode}): {result.stdout}\n{result.stderr}')
    return result


def start_probe(command: list[str], mode: str, root: Path) -> subprocess.Popen:
    return subprocess.Popen(command + [mode, str(root)], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding='utf-8', errors='replace',
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)


def wait_for(path: Path, process: subprocess.Popen) -> None:
    deadline = time.monotonic() + 15
    while not path.exists():
        if process.poll() is not None:
            stdout, stderr = process.communicate()
            raise RuntimeError(f'Probe exited before {path.name}: {stdout}\n{stderr}')
        if time.monotonic() >= deadline:
            raise TimeoutError(f'Timed out waiting for {path.name}')
        time.sleep(0.02)


def finish_probe(process: subprocess.Popen, mode: str) -> None:
    stdout, stderr = process.communicate(timeout=15)
    if process.returncode or f'PASS {mode}' not in stdout:
        raise AssertionError(f'{mode} failed ({process.returncode}): {stdout}\n{stderr}')


def journal_files(root: Path) -> dict[str, bytes]:
    paths = [root / 'radio-state.json']
    temporary = root / 'radio-state.json.tmp'
    if temporary.exists():
        paths.append(temporary)
    archives = root / 'radio-state.json.archives'
    if archives.exists():
        paths.extend(sorted(path for path in archives.rglob('*') if path.is_file()))
    return {path.relative_to(root).as_posix(): path.read_bytes() for path in paths}


def lock_scenario(command: list[str], root: Path, terminate_holder: bool) -> dict:
    root.mkdir()
    run_probe(command, 'seed', root)
    before = journal_files(root)
    holder = start_probe(command, 'hold', root)
    try:
        wait_for(root / 'holding', holder)
        run_probe(command, 'reject', root)
        if not (root / 'rejected').is_file() or journal_files(root) != before:
            raise AssertionError('Locked load/save changed the committed radio state')
        if terminate_holder:
            holder.terminate()
            holder.communicate(timeout=15)
        else:
            (root / 'release').write_text('release', encoding='utf-8')
            finish_probe(holder, 'hold')
        run_probe(command, 'acquire', root)
        if not (root / 'acquired').is_file() or journal_files(root) != before:
            raise AssertionError('Load/save after release failed or changed the restored snapshot')
        return {'holderTerminated': terminate_holder, 'loadRejectedWhileHeld': True,
                'saveRejectedWhileHeld': True, 'loadAndSaveAfterRelease': True,
                'committedBytesPreserved': True, 'holderExitCode': holder.returncode}
    finally:
        if holder.poll() is None:
            holder.kill()
        holder.communicate(timeout=10)


def stale_scenario(command: list[str], root: Path) -> dict:
    root.mkdir()
    run_probe(command, 'seed', root)
    stale = start_probe(command, 'stale', root)
    try:
        wait_for(root / 'loaded', stale)
        run_probe(command, 'rotate', root)
        before = journal_files(root)
        manifest = json.loads(before['radio-state.json'])
        references = manifest.get('archives', [])
        if manifest.get('schemaVersion') != 2 or len(references) != 1:
            raise AssertionError('Writer did not publish a v2 journal with one archive')
        reference = references[0]
        archive_name = f"radio-state.json.archives/segment-{reference['generation']:04d}.json"
        archive = before[archive_name]
        if reference['bytes'] != len(archive) or reference['sha256'] != hashlib.sha256(archive).hexdigest():
            raise AssertionError('Committed archive does not match its manifest reference')
        (root / 'attempt-stale-save').write_text('attempt', encoding='utf-8')
        finish_probe(stale, 'stale')
        if not (root / 'stale-rejected').is_file() or journal_files(root) != before:
            raise AssertionError('Stale writer changed the manifest or archive bytes')
        run_probe(command, 'verify', root)
        if journal_files(root) != before:
            raise AssertionError('Recovery or duplicate/conflict checks changed journal bytes')
        return {'staleLoadPrecededOtherJvmRotation': True, 'staleSaveRejected': True,
                'manifestAndArchiveBytesPreserved': True, 'journalRecovered': True,
                'archivedRetrySuppressed': True, 'archivedPayloadConflictRejected': True,
                'archiveCount': 1,
                'committedFiles': {name: {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                                   for name, data in before.items()}}
    finally:
        if stale.poll() is None:
            stale.kill()
        stale.communicate(timeout=10)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--plugin-jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    jdk = args.java_home.resolve()
    jar = args.plugin_jar.resolve()
    extension = '.exe' if os.name == 'nt' else ''
    java = jdk / 'bin' / f'java{extension}'
    compiler = jdk / 'bin' / f'javac{extension}'
    for required in (java, compiler, jar):
        if not required.is_file():
            raise FileNotFoundError(required)
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    source_bytes = PROBE_SOURCE.encode('utf-8')
    with tempfile.TemporaryDirectory(prefix='stargenesis-radio-journal-lock-') as temporary:
        directory = Path(temporary)
        source = directory / 'RadioJournalProcessProbe.java'
        source.write_bytes(source_bytes)
        compilation = subprocess.run(
            [str(compiler), '-J-Xms8m', '-J-Xmx64m', '-J-XX:+UseSerialGC', '-J-XX:TieredStopAtLevel=1',
             '-J-XX:ReservedCodeCacheSize=8m', '-J-XX:MaxMetaspaceSize=64m', '--release', '21',
             '-cp', str(jar), '-d', str(directory), str(source)],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if compilation.returncode:
            raise RuntimeError(f'Probe compilation failed: {compilation.stdout}\n{compilation.stderr}')
        command = [str(java), '-Xms8m', '-Xmx64m', '-Xss256k', '-XX:+UseSerialGC',
                   '-XX:TieredStopAtLevel=1', '-XX:ReservedCodeCacheSize=8m', '-XX:MaxMetaspaceSize=64m',
                   '-XX:ActiveProcessorCount=2', '-cp', os.pathsep.join((str(directory), str(jar))),
                   'dev.stargenesis.paper.communication.RadioJournalProcessProbe']
        cases = [lock_scenario(command, directory / 'normal', False),
                 lock_scenario(command, directory / 'terminated', True),
                 stale_scenario(command, directory / 'stale-writer')]
        version = subprocess.run([str(java), '-Xmx32m', '-XX:+UseSerialGC', '-version'], check=True,
                                 capture_output=True, text=True, timeout=15,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0).stderr.strip()
    if hashlib.sha256(jar.read_bytes()).hexdigest() != jar_hash:
        raise AssertionError('Plugin JAR changed during the process probes')
    result = {'passed': True, 'java': version, 'pluginSha256': jar_hash,
              'probeSha256': hashlib.sha256(source_bytes).hexdigest(), 'cases': cases,
              'scope': 'Two local JVMs sharing temporary files. Checks radio file-lock rejection/release, '
                       'stale-writer CAS and committed manifest/archive preservation with recovery and retry checks. '
                       'No Paper/Folia lifecycle, network filesystem, production capacity or real asset acceptance.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
