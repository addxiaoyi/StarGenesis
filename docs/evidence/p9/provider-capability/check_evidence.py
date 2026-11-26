from pathlib import Path
import hashlib,json,subprocess,sys
root=Path(__file__).resolve().parents[2]
sha=lambda content:hashlib.sha256(content).hexdigest()
checks=0
for relative in ('docs/evidence/p9/provider-capability','docs/evidence/p11/rc5-modular-build/ci'):
    folder=root/relative
    summary=json.loads((folder/'summary.json').read_text(encoding='utf-8'))
    for name,expected in summary['evidenceHashes'].items():
        assert sha((folder/name).read_bytes())==expected,name
        checks+=1
    for name,expected in summary.get('sourceHashes',{}).items():
        assert sha((root/name).read_bytes())==expected,name
        checks+=1
    if 'builds' in summary:
        for label,build in summary['builds'].items():
            assert build['tests']=={'tests':646,'failures':0,'errors':0,'skipped':0}
            assert build['reports']==86
            assert sha((root/'.work/settlement-capability'/label/'StarGenesis-4.0.0-SNAPSHOT.jar').read_bytes())==build['jarSha256']
            checks+=1
if '--staged' in sys.argv:
    names=subprocess.check_output(['git','diff','--cached','--name-only','-z'],cwd=root).decode().split('\0')
    for name in filter(None,names):
        content=(root/name).read_bytes()
        staged=subprocess.check_output(['git','show',':'+name],cwd=root)
        if name=='.gitattributes':content=content.replace(b'\r\n',b'\n')
        assert content==staged,name
        checks+=1
print(json.dumps({'passed':True,'checks':checks,'staged':'--staged' in sys.argv}))
