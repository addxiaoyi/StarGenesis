"""Build a temporary Paper plugin that exercises StarGenesis live disable cleanup."""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--paper-api-jar", type=Path, required=True)
    parser.add_argument("--server-libraries", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    fixture = root / "scripts" / "fixtures" / "lab_disable_trigger"
    source = fixture / "LabDisableTrigger.java"
    descriptor = fixture / "plugin.yml"
    api_jar = args.paper_api_jar.resolve()
    libraries = args.server_libraries.resolve()
    java_home = args.java_home.resolve()
    suffix = ".exe" if os.name == "nt" else ""
    javac = java_home / "bin" / f"javac{suffix}"
    jar_tool = java_home / "bin" / f"jar{suffix}"
    if not source.is_file() or not descriptor.is_file() or not api_jar.is_file() or not libraries.is_dir():
        parser.error("Fixture source, Paper API JAR, and server libraries must exist")
    classpath = [str(api_jar), *(str(path.resolve()) for path in libraries.rglob("*.jar"))]
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="lab-disable-fixture-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        subprocess.run([
            str(javac), "-encoding", "UTF-8", "--release", "21", "-classpath",
            os.pathsep.join(classpath), "-d", str(classes), str(source),
        ], check=True)
        shutil.copy2(descriptor, classes / "plugin.yml")
        subprocess.run([str(jar_tool), "--create", "--file", str(output), "-C", str(classes), "."], check=True)
    print(output)


if __name__ == "__main__":
    main()
