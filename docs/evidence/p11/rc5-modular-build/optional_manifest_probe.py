from pathlib import Path
import hashlib,json,shutil,subprocess,tempfile,sys
work=Path(__file__).resolve().parent;root=work.parents[1]
sys.path.insert(0,str(root/'scripts'))
from test_release_artifact_signer import run,sha256
artifacts=[*sorted((work/'first').glob('*.jar')),work/'first/sbom.json']
for label,directory in [('java21','jdk-21.0.13.0.2+1'),('java25','jdk-25.0.5.0.2+1')]:
    java=root.parent/'.toolchains'/directory/'bin/java.exe'
    checks=[]
    with tempfile.TemporaryDirectory(prefix='sg-optional-sign-') as temporary:
        folder=Path(temporary);private=folder/'test-private.pem';public=folder/'test-public.pem';manifest=folder/'manifest.txt'
        copied=[]
        for path in artifacts:
            dest=folder/path.name;shutil.copyfile(path,dest);copied.append(dest)
        run(java,root,['keygen',str(private),str(public)],True)
        run(java,root,['sign',str(private),'smoke.optional','4.0.0-rc.5',str(manifest),*[str(p) for p in copied]],True)
        run(java,root,['verify',str(public),str(manifest)],True)
        for dest in copied:
            original=dest.read_bytes();dest.write_bytes(original+b'tampered')
            run(java,root,['verify',str(public),str(manifest)],False);dest.write_bytes(original)
            checks.append(dest.name+' tamper rejected')
        run(java,root,['verify',str(public),str(manifest)],True)
    assert not private.exists()
    result={'passed':True,'testKeyEphemeral':True,'privateTestKeyRemoved':True,'version':'4.0.0-rc.5',
            'files':{p.name:sha256(p) for p in artifacts},'checks':checks,
            'scope':'Temporary copies of all four candidate JARs and SBOM signed and individually tampered with a disposable test key. No official release signature.'}
    (work/f'optional-manifest-{label}.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(label,'all four optional-release artifacts and SBOM verified',flush=True)
