"""Exercise Ed25519 release signing and artifact tamper detection with an ephemeral key."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


SIGNER = Path(__file__).with_name("ReleaseArtifactSigner.java")


def run(java: Path, root: Path, arguments: list[str], expect_success: bool) -> bool:
    result = subprocess.run(
        [str(java), str(SIGNER), *arguments],
        cwd=root,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    if expect_success and result.returncode != 0:
        raise RuntimeError(result.stdout + result.stderr)
    if not expect_success and result.returncode == 0:
        raise RuntimeError("Tampered artifact unexpectedly passed release verification")
    return result.returncode != 0


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--test-version", default="1.0.0-rc.1",
                        help="Non-SNAPSHOT version embedded in the ephemeral test manifest")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    java = args.java.resolve()
    plugin_jar = args.plugin_jar.resolve()
    sbom = args.sbom.resolve()
    if not SIGNER.is_file() or not plugin_jar.is_file() or not sbom.is_file():
        parser.error("Release signer source, plugin JAR, and SBOM must exist")

    with tempfile.TemporaryDirectory(prefix="stargenesis-release-signer-") as temporary:
        directory = Path(temporary)
        release_jar = directory / f"StarGenesis-{args.test_version}.jar"
        release_sbom = directory / "sbom.json"
        private_key = directory / "release-private.pem"
        public_key = directory / "release-public.pem"
        manifest = directory / "release-manifest.txt"
        shutil.copy2(plugin_jar, release_jar)
        shutil.copy2(sbom, release_sbom)

        run(java, root, ["keygen", str(private_key), str(public_key)], True)
        run(java, root, ["sign", str(private_key), "smoke.release", args.test_version, str(manifest),
                         str(release_jar), str(release_sbom)], True)
        run(java, root, ["verify", str(public_key), str(manifest)], True)
        release_sbom.write_bytes(release_sbom.read_bytes() + b"tampered\n")
        tamper_rejected = run(java, root, ["verify", str(public_key), str(manifest)], False)
    private_key_removed = not private_key.exists()

    result = {
        "passed": tamper_rejected and private_key_removed,
        "algorithm": "Ed25519",
        "testVersion": args.test_version,
        "testKeyEphemeral": True,
        "signedArtifacts": [release_jar.name, "sbom.json"],
        "signatureVerified": True,
        "tamperRejected": tamper_rejected,
        "privateTestKeyRemoved": private_key_removed,
        "sourceJarSha256": sha256(plugin_jar),
        "sourceSbomSha256": sha256(sbom),
        "scope": "Ephemeral-key signer self-test. No official release key or release artifact was signed.",
    }
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError) as error:
        print(f"Release signer self-test failed: {error}", file=sys.stderr)
        raise SystemExit(1)
