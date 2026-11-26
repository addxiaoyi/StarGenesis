from datetime import datetime
from pathlib import Path
import json
import subprocess
import sys

root = Path.cwd()
work = root / '.work/radio-journal'
stamp = datetime.now().strftime('%Y%m%dT%H%M%S')
for name in ('batch-stdout.log', 'batch-stderr.log', 'verify-java21.log', 'verify-java25.log'):
    source = work / name
    if source.exists():
        source.rename(work / (stamp + '-' + name))
with (work / 'batch-stdout.log').open('w') as out, (work / 'batch-stderr.log').open('w') as err:
    process = subprocess.Popen([sys.executable, str(work / 'verify_batch.py')], cwd=root, stdout=out, stderr=err,
                               creationflags=subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP)
result = {'pid': process.pid, 'startedAt': datetime.now().isoformat(), 'script': '.work/radio-journal/verify_batch.py'}
(work / 'process.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
