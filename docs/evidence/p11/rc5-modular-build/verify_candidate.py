from pathlib import Path
import hashlib,json,sys,zipfile
root=Path(__file__).resolve().parents[2]
work=Path(__file__).resolve().parent
first=json.loads((work/'first.json').read_text(encoding='utf-8'))
second=json.loads((work/'second.json').read_text(encoding='utf-8'))
assert first['tests']==second['tests']=={'reports':85,'tests':634,'failures':0,'errors':0,'skipped':0}
assert first['profiles']==second['profiles']==['helstera-bridge','craftengine-bridge','cookery-bridge']
assert first['sourceTreeSha256']==second['sourceTreeSha256']
assert first['sbom']['sha256']==second['sbom']['sha256']
assert first['artifacts'].keys()==second['artifacts'].keys()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
for module in first['artifacts']:
    assert first['artifacts'][module]['sha256']==second['artifacts'][module]['sha256'],module
    for build in (first,second):
        artifact=build['artifacts'][module]
        assert sha(root/artifact['file'])==artifact['sha256']
for build in (first,second):
    for name,entry in build['dependencyPreparation'].items():
        for filename,expected in entry['files'].items():
            assert sha(root/entry['directory']/filename)==expected
    assert build['signed'] is False
    assert build['sourceVersion']=='4.0.0-SNAPSHOT'
result={'passed':True,'version':first['version'],'tests':first['tests'],'profiles':first['profiles'],
        'sourceTreeSha256':first['sourceTreeSha256'],'sbomSha256':first['sbom']['sha256'],
        'artifacts':{module:entry['sha256'] for module,entry in first['artifacts'].items()},
        'scope':'Two independent local Java21 source copies and actual API preparations; all four plugin JARs and SBOM match. Not a production signature or cross-platform reproducibility claim.'}
(work/'reproducibility.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result))
