"""Exercise the signed-design publisher CLI with an ephemeral key and a bundled design."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


SIGNER = Path(__file__).with_name("SignedDesignPackageSigner.java")


def run(java: Path, classpath: str, arguments: list[str], expect_success: bool) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [str(java), "-cp", classpath, "SignedDesignPackageSigner", *arguments],
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    if expect_success and result.returncode != 0:
        raise RuntimeError(result.stdout + result.stderr)
    if not expect_success and result.returncode == 0:
        raise RuntimeError("Tampered signed design unexpectedly passed verification")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--design", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    java_home = args.java_home.resolve()
    java = args.java.resolve()
    plugin_jar = args.plugin_jar.resolve()
    design = args.design.resolve()
    if not SIGNER.is_file() or not plugin_jar.is_file() or not design.is_file():
        parser.error("Signer source, shaded plugin JAR, and design example must exist")
    compiler = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    if not compiler.is_file():
        parser.error(f"JDK compiler not found: {compiler}")

    with tempfile.TemporaryDirectory(prefix="stargenesis-design-signer-") as temporary:
        directory = Path(temporary)
        classes = directory / "classes"
        classes.mkdir()
        subprocess.run([
            str(compiler), "-encoding", "UTF-8", "--release", "21", "-classpath", str(plugin_jar),
            "-d", str(classes), str(SIGNER),
        ], check=True)
        classpath = str(plugin_jar) + os.pathsep + str(classes)
        private_key = directory / "publisher-private.pem"
        public_key = directory / "publisher-public.pem"
        package = directory / "signed-design.json"
        key_id = "publisher.smoke"

        run(java, classpath, ["keygen", str(private_key), str(public_key)], True)
        run(java, classpath, ["sign", str(private_key), key_id, str(design), str(package)], True)
        verified = run(java, classpath, ["verify", str(public_key), key_id, str(package)], True)
        wrong_key_rejected = run(java, classpath, ["verify", str(public_key), "publisher.wrong", str(package)], False)
        envelope = json.loads(package.read_text(encoding="utf-8"))
        signature = envelope["signature"]
        envelope["signature"] = ("A" if signature[0] != "A" else "B") + signature[1:]
        package.write_text(json.dumps(envelope, separators=(",", ":")), encoding="utf-8")
        tamper_rejected = run(java, classpath, ["verify", str(public_key), key_id, str(package)], False)

    result = {
        "passed": True,
        "algorithm": "Ed25519",
        "publisherKeyId": key_id,
        "designSourceSha256": hashlib.sha256(design.read_bytes()).hexdigest(),
        "validSignatureVerified": verified.returncode == 0,
        "signatureTamperRejected": tamper_rejected.returncode != 0,
        "unknownKeyIdRejected": wrong_key_rejected.returncode != 0,
        "privateTestKeyRemoved": not private_key.exists(),
        "testKeyEphemeral": True,
        "scope": "Ephemeral-key design package signing self-test. The key was removed and is not an operational publisher trust root.",
    }
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Signed design signer self-test failed: {error}", file=sys.stderr)
        raise SystemExit(1)
