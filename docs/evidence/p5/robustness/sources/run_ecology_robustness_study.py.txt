#!/usr/bin/env python3
"""Run the v2 robustness study with an immutable artifact/source binding for every segment."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid


JVM_OPTIONS = ["-Xms16m", "-Xmx96m", "-Xss512k", "-XX:+UseSerialGC",
               "-XX:CICompilerCount=2", "-XX:ReservedCodeCacheSize=64m",
               "-XX:MaxMetaspaceSize=96m", "-XX:ActiveProcessorCount=2"]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _read_manifest(path: Path) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate runtime manifest key: {key}")
            result[key] = value
        return result
    manifest = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    if (type(manifest) is not dict or any(type(manifest.get(field)) is not int
            for field in ("schemaVersion", "baselineSeeds", "pairedSeeds"))):
        raise ValueError("Runtime manifest has invalid numeric fields")
    return manifest


def _require_unchanged(binding: dict, runtime: Path) -> None:
    if _read_manifest(runtime) != binding:
        raise RuntimeError("Study runtime binding changed")
    for path_key, hash_key in (("java", "javaSha256"), ("pluginJar", "pluginJarSha256"),
                               ("workerSource", "workerSourceSha256")):
        if sha256(Path(binding[path_key])) != binding[hash_key]:
            raise RuntimeError(f"Study {path_key} bytes changed; refusing mixed-runtime evidence")


@contextmanager
def _exclusive_run(output: Path):
    """Serialize cooperating wrappers; a crashed owner leaves a fail-closed lock for inspection."""
    lock = output.with_name(output.name + ".run.lock")
    token = f"{os.getpid()}:{uuid.uuid4().hex}\n"
    try:
        stream = lock.open("x", encoding="ascii", newline="\n")
    except FileExistsError as failure:
        raise ValueError("Study run lock exists; inspect the owner before removing a stale lock") from failure
    try:
        stream.write(token)
        stream.flush()
        os.fsync(stream.fileno())
        yield
    finally:
        stream.close()
        if not lock.is_file() or lock.read_text(encoding="ascii") != token:
            raise RuntimeError("Study run lock changed; refusing to remove another owner's lock")
        lock.unlink()


def run_study(java: Path, plugin_jar: Path, expected_jar_sha256: str, output: Path,
              baseline_seeds: int = 64, paired_seeds: int = 32, resume: bool = False,
              *, repository_root: Path | None = None) -> dict:
    """Invoke one worker segment; tests may supply a temporary repository and mock subprocess.run."""
    if type(expected_jar_sha256) is not str or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_jar_sha256):
        raise ValueError("expected-jar-sha256 must be exactly 64 hexadecimal characters")
    expected_jar_sha256 = expected_jar_sha256.lower()
    if (type(baseline_seeds) is not int or type(paired_seeds) is not int
            or not 1 <= paired_seeds <= baseline_seeds <= 128):
        raise ValueError("Seed budgets must satisfy 1 <= paired <= baseline <= 128")
    root = (repository_root or Path(__file__).resolve().parents[1]).resolve(strict=True)
    java = Path(java).resolve(strict=True)
    plugin_jar = Path(plugin_jar).resolve(strict=True)
    worker = (root / "scripts/EcologyRobustnessStudy.java").resolve(strict=True)
    for path in (java, plugin_jar, worker):
        if not path.is_file():
            raise ValueError(f"Study input is not a regular file: {path}")
    output = Path(output).resolve()
    plan = output.with_name(output.name + ".plan.json")
    runtime = output.with_name(output.name + ".runtime.json")
    if resume:
        if not all(path.is_file() for path in (output, plan, runtime)):
            raise ValueError("Resume requires the original report, plan and runtime manifest")
    elif any(path.exists() for path in (output, plan, runtime)):
        raise ValueError("Refusing to overwrite prior report, plan or runtime manifest")
    actual_jar_sha256 = sha256(plugin_jar)
    if actual_jar_sha256 != expected_jar_sha256:
        raise ValueError("Plugin JAR SHA256 differs from the required expected hash")
    binding = {"schemaVersion": 1, "java": str(java), "javaSha256": sha256(java),
               "pluginJar": str(plugin_jar),
               "pluginJarSha256": actual_jar_sha256, "workerSource": str(worker),
               "workerSourceSha256": sha256(worker), "output": str(output), "plan": str(plan),
               "baselineSeeds": baseline_seeds, "pairedSeeds": paired_seeds,
               "jvmOptions": JVM_OPTIONS[:]}
    if resume:
        if _read_manifest(runtime) != binding:
            raise ValueError("Resume runtime binding differs from the original artifact, source, paths or seed budgets")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation arbitrates competing first-run wrappers; never replace an old binding.
        with runtime.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(binding, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
    command = [str(java), *JVM_OPTIONS, "-cp", str(plugin_jar), str(worker), str(output),
               str(baseline_seeds), str(paired_seeds)]
    if resume:
        command.append("--resume")
    with _exclusive_run(output):
        _require_unchanged(binding, runtime)
        try:
            # Inherit stdout/stderr so progress is streamed. No shell or command-string interpolation.
            subprocess.run(command, cwd=str(root), shell=False, check=True)
        finally:
            # Also check on failure: changed input must not silently continue a later segment.
            _require_unchanged(binding, runtime)
        if not output.is_file() or not plan.is_file():
            raise RuntimeError("Worker exited successfully without a committed report and plan")
    return binding


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--expected-jar-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline-seeds", type=int, default=64)
    parser.add_argument("--paired-seeds", type=int, default=32)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args(argv)
    try:
        binding = run_study(args.java, args.plugin_jar, args.expected_jar_sha256, args.output,
                            args.baseline_seeds, args.paired_seeds, args.resume)
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"Study runtime rejected: {failure}\n")
    print(json.dumps({"passed": True, "pluginJarSha256": binding["pluginJarSha256"],
                      "workerSourceSha256": binding["workerSourceSha256"],
                      "resumed": args.resume}))


if __name__ == "__main__":
    main()
