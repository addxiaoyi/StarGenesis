"""Check settlement file-lock scope across two bounded JVMs, including holder process termination."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def wait_for(path: Path, process: subprocess.Popen) -> None:
    deadline = time.monotonic() + 15
    while not path.exists():
        if process.poll() is not None:
            stdout, stderr = process.communicate()
            raise RuntimeError(f"Probe exited before {path.name}: {stdout}\n{stderr}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Timed out waiting for {path.name}")
        time.sleep(0.02)


def scenario(java: Path, classpath: str, root: Path, terminate_holder: bool) -> dict:
    root.mkdir()
    command = [str(java), '-Xms8m', '-Xmx32m', '-Xss256k', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1',
               '-XX:ReservedCodeCacheSize=8m', '-XX:MaxMetaspaceSize=32m', '-XX:ActiveProcessorCount=2',
               '-cp', classpath, 'SettlementLockProbe']
    processes = []
    creationflags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    try:
        holder = subprocess.Popen(command + ['hold', str(root)], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  text=True, encoding='utf-8', errors='replace', creationflags=creationflags)
        processes.append(holder)
        wait_for(root / 'holding', holder)
        contender = subprocess.Popen(command + ['acquire', str(root)], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     text=True, encoding='utf-8', errors='replace', creationflags=creationflags)
        processes.append(contender)
        wait_for(root / 'attempting', contender)
        time.sleep(0.3)
        if (root / 'acquired').exists() or contender.poll() is not None:
            raise AssertionError('Contender entered or exited while another process held the saga lock')
        if terminate_holder:
            holder.terminate()
        else:
            (root / 'release').write_text('release', encoding='utf-8')
        holder_output = holder.communicate(timeout=10)
        contender_output = contender.communicate(timeout=10)
        if (not terminate_holder and holder.returncode != 0) or contender.returncode != 0:
            raise RuntimeError(f"Probe failed: holder={holder_output}, contender={contender_output}")
        if not (root / 'acquired').exists():
            raise AssertionError('Contender did not acquire after the holder released or terminated')
        return {'holderTerminated': terminate_holder, 'blockedWhileHeld': True, 'acquiredAfterRelease': True,
                'holderExitCode': holder.returncode, 'contenderExitCode': contender.returncode}
    finally:
        for process in processes:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=10)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--plugin-jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    java_home = args.java_home.resolve()
    jar = args.plugin_jar.resolve()
    source = Path(__file__).with_name('SettlementLockProbe.java')
    extension = '.exe' if os.name == 'nt' else ''
    java = java_home / 'bin' / f'java{extension}'
    compiler = java_home / 'bin' / f'javac{extension}'
    with tempfile.TemporaryDirectory(prefix='stargenesis-settlement-lock-') as temporary:
        directory = Path(temporary)
        subprocess.run([str(compiler), '-J-Xms8m', '-J-Xmx64m', '-J-XX:+UseSerialGC', '-J-XX:TieredStopAtLevel=1',
                        '-J-XX:ReservedCodeCacheSize=8m', '-J-XX:MaxMetaspaceSize=64m',
                        '--release', '21', '-cp', str(jar), '-d', str(directory), str(source)],
                       check=True, capture_output=True, text=True, timeout=30)
        classpath = os.pathsep.join((str(directory), str(jar)))
        cases = [scenario(java, classpath, directory / 'normal', False),
                 scenario(java, classpath, directory / 'terminated', True)]
        version = subprocess.run([str(java), '-Xmx32m', '-XX:+UseSerialGC', '-version'],
                                 check=True, capture_output=True, text=True, timeout=15).stderr.strip()
    result = {'passed': True, 'java': version, 'pluginSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'probeSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'cases': cases,
              'scope': 'Two local JVMs sharing a temporary filesystem. Tests lock exclusion and release only; no real provider, network filesystem, production capacity or asset-transfer acceptance.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
