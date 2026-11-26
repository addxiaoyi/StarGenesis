from pathlib import Path
import hashlib, json, subprocess, sys
root = next(p for p in Path(__file__).resolve().parents if (p/'pom.xml').is_file() and (p/'scripts').is_dir())
directory = root/'docs/evidence/p10/recipe-catalog'
summary = json.loads((directory/'summary.json').read_text(encoding='utf-8'))
sha = lambda b: hashlib.sha256(b).hexdigest()
normalized = []
checked = 0
for group, base in (('sourceHashes',root),('evidenceHashes',directory)):
    for name, expected in summary[group].items():
        path = base/name
        raw = path.read_bytes()
        assert sha(raw) == expected, str(path)
        if '--staged' in sys.argv:
            staged = subprocess.check_output(['git','show',':'+path.relative_to(root).as_posix()],cwd=root)
            if raw != staged:
                assert group == 'sourceHashes' and name != 'scripts/craftengine-api-lock.json' and raw.replace(b'\r\n',b'\n') == staged, 'Index differs: '+str(path)
                normalized.append(name)
        checked += 1
if '--staged' in sys.argv:
    assert subprocess.check_output(['git','show',':docs/evidence/p10/recipe-catalog/summary.json'],cwd=root) == (directory/'summary.json').read_bytes()
lock = sha((root/'scripts/craftengine-api-lock.json').read_bytes())
for path in ('stargenesis-integrations/src/main/java/dev/stargenesis/integrations/CapabilityRegistry.java',
             'stargenesis-craftengine/src/main/java/dev/stargenesis/craftengine/CraftEngineBridge.java'):
    assert 'sha256:'+lock in (root/path).read_text(encoding='utf-8')
print(json.dumps({'passed':True,'hashesChecked':checked,'staged':'--staged' in sys.argv,'indexOnlyLineEndingNormalization':normalized}))
