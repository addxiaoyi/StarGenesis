"""Check worker ownership across JVMs and reload classloaders, including shutdown timeout and process death."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def scenario(command: list[str], root: Path, mode: str, terminate: bool) -> dict:
    root.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    holder = subprocess.Popen(command + [mode, str(root)], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              text=True, encoding='utf-8', errors='replace', creationflags=flags)
    try:
        deadline = time.monotonic() + 15
        while not (root / 'holding').exists():
            if holder.poll() is not None:
                raise RuntimeError(f'Holder stopped before acquiring: {holder.communicate()}')
            if time.monotonic() >= deadline:
                raise TimeoutError('Holder did not acquire the directory in 15 seconds')
            time.sleep(0.02)
        if mode == 'timed-out' and not (root / 'incomplete').exists():
            raise AssertionError('Expected shutdown timeout was not recorded')
        rejected = subprocess.run(command + ['reject', str(root)], capture_output=True, text=True,
                                  encoding='utf-8', errors='replace', timeout=15, creationflags=flags)
        if rejected.returncode or not (root / 'rejected').exists():
            raise AssertionError(f'Contender was not rejected: {rejected.stdout}\n{rejected.stderr}')
        if terminate:
            holder.terminate()
        else:
            (root / 'release').write_text('release', encoding='utf-8')
        output = holder.communicate(timeout=10)
        if not terminate and (holder.returncode or not (root / 'released').exists()):
            raise AssertionError(f'Holder did not close cleanly: {output}')
        acquired = subprocess.run(command + ['acquire', str(root)], capture_output=True, text=True,
                                  encoding='utf-8', errors='replace', timeout=15, creationflags=flags)
        if acquired.returncode or not (root / 'acquired').exists():
            raise AssertionError(f'New owner could not acquire: {acquired.stdout}\n{acquired.stderr}')
        return {'shutdownTimedOut': mode == 'timed-out', 'holderTerminated': terminate,
                'sameJvmAndReloadClassloaderRejected': True, 'otherProcessRejectedWhileHeld': True,
                'acquiredAfterRelease': True, 'holderExitCode': holder.returncode}
    finally:
        if holder.poll() is None:
            holder.kill()
        holder.communicate(timeout=10)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--plugin-jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    jdk = args.java_home.resolve()
    jar = args.plugin_jar.resolve()
    source = Path(__file__).with_name('WorkerDirectoryLockProbe.java')
    extension = '.exe' if os.name == 'nt' else ''
    java = jdk / 'bin' / f'java{extension}'
    compiler = jdk / 'bin' / f'javac{extension}'
    with tempfile.TemporaryDirectory(prefix='stargenesis-worker-lock-') as temporary:
        directory = Path(temporary)
        subprocess.run([str(compiler), '-J-Xms8m', '-J-Xmx64m', '-J-XX:+UseSerialGC', '-J-XX:TieredStopAtLevel=1',
                        '-J-XX:ReservedCodeCacheSize=8m', '-J-XX:MaxMetaspaceSize=64m', '--release', '21',
                        '-cp', str(jar), '-d', str(directory), str(source)],
                       check=True, capture_output=True, text=True, timeout=30)
        command = [str(java), '-Xms8m', '-Xmx32m', '-Xss256k', '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1',
                   '-XX:ReservedCodeCacheSize=8m', '-XX:MaxMetaspaceSize=32m', '-XX:ActiveProcessorCount=2',
                   '-cp', os.pathsep.join((str(directory), str(jar))), 'WorkerDirectoryLockProbe']
        cases = [scenario(command, directory / 'normal', 'hold', False),
                 scenario(command, directory / 'timeout', 'timed-out', False),
                 scenario(command, directory / 'killed', 'timed-out', True)]
        version = subprocess.run([str(java), '-Xmx32m', '-XX:+UseSerialGC', '-version'], check=True,
                                 capture_output=True, text=True, timeout=15).stderr.strip()
    result = {'passed': True, 'java': version, 'pluginSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'probeSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'cases': cases,
              'scope': 'Temporary local filesystem, independent JVMs and plugin-style classloaders. No Paper/Folia lifecycle, network filesystem, malicious plugin or production capacity acceptance.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
