from pathlib import Path
import hashlib,json,subprocess,sys
root=Path(__file__).resolve().parents[2]
folder=root/'docs/evidence/p11/rc5-modular-build'
summary=json.loads((folder/'summary.json').read_text(encoding='utf-8'))
sha=lambda data:hashlib.sha256(data).hexdigest()
checked=0
for name,expected in summary['evidenceHashes'].items():
    assert sha((folder/name).read_bytes())==expected,name
    checked+=1
for name,expected in summary['sourceHashes'].items():
    assert sha((root/name).read_bytes())==expected,name
    checked+=1
inputs=json.loads((folder/'candidate-inputs.json').read_text())
for name,expected in inputs['files'].items():
    file=folder/'source-input-gitattributes.txt' if name=='.gitattributes' else root/name
    assert sha(file.read_bytes())==expected,name
    checked+=1
expected_counts={'cookery':[40,42],'item':[46,46],'helstera':[47],'folia':[22]}
for mode,result in summary['serverResults'].items():
    assert result['passed']
    runs=result.get('runs',[result])
    counts=[len(run.get('fixture',{}).get('checks',run.get('checks',{}))) for run in runs]
    assert counts==expected_counts[mode],(mode,counts)
    for run in runs:
        if mode in ('cookery','item'):
            log=folder/f'servers/{mode}/console-{run["run"]}.log'
        else:
            log=folder/f'servers/{mode}'/('smoke-console.log' if mode=='folia' else 'console.log')
        expected=run.get('logSha256',run.get('log_sha256'))
        assert sha(log.read_bytes())==expected
        checked+=1
for label in ('first','second'):
    build=json.loads((folder/label/'build.json').read_text())
    assert sha((folder/label/'release-verify.log').read_bytes())==build['buildLogSha256']
    assert sha((folder/label/'sbom.json').read_bytes())==build['sbom']['sha256']
    for provider,entry in build['dependencyPreparation'].items():
        for name,expected in entry['files'].items():
            assert sha((folder/label/'dependency-evidence'/provider/name).read_bytes())==expected
            checked+=1
if '--staged' in sys.argv:
    staged=subprocess.check_output(['git','diff','--cached','--name-only','-z'],cwd=root).decode().split('\0')
    for name in filter(None,staged):
        actual=(root/name).read_bytes()
        staged_bytes=subprocess.check_output(['git','show',':'+name],cwd=root)
        # Ordinary .gitattributes follows git text normalization; evidence must be exact.
        if name=='.gitattributes':
            assert actual.replace(b'\r\n',b'\n')==staged_bytes
        else:
            assert actual==staged_bytes,name
        checked+=1
result={'passed':True,'checks':checked,'evidenceFiles':len(summary['evidenceHashes']),
        'sourceFiles':len(summary['sourceHashes']),'candidateInputs':len(inputs['files']),
        'staged':'--staged' in sys.argv,'serverChecks':expected_counts}
print(json.dumps(result))
