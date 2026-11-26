from pathlib import Path
import hashlib,json,shutil,subprocess
work=Path(__file__).resolve().parent;root=work.parents[1]
target=root/'docs/evidence/p5/dynamic-lifecycle';target.mkdir(parents=True,exist_ok=True)
with (work/'python-tests.log').open('w',encoding='utf-8') as log:
    subprocess.run([str(root/'.work/python-3.12.10/python.exe'),'-m','unittest','discover','-s','scripts','-p','test_verify_ecology_robustness_study.py'],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
assert 'Ran 18 tests' in (work/'python-tests.log').read_text()
names=['python-tests.log','test_dynamic.py','archive_dynamic.py']
for label in ('java21','java25'):
    result=json.loads((work/f'dynamic-{label}-first.json').read_text())
    assert result['tests']=={'tests':6,'failures':0,'errors':0,'skipped':0}
    names += [f'dynamic-{label}-first.{extension}' for extension in ('json','log','xml')]
files={}
for name in names:
    data=(work/name).read_bytes();destination=target/name
    if destination.exists():assert destination.read_bytes()==data,name
    else:shutil.copyfile(work/name,destination)
    files[name]=hashlib.sha256(data).hexdigest()
sources=['stargenesis-sim/src/test/java/dev/stargenesis/sim/EcologyDynamicLifecycleV2Test.java',
         'stargenesis-sim/src/main/java/dev/stargenesis/sim/EcologyWorld.java',
         'stargenesis-sim/src/main/java/dev/stargenesis/sim/EcologyConfig.java',
         'scripts/EcologyRobustnessStudy.java','scripts/verify_ecology_robustness_study.py',
         'scripts/test_verify_ecology_robustness_study.py','.github/workflows/verify.yml']
summary={'date':'2026-11-26','baseCommit':'b3ea8523a1366c5c600094b0f02091d932c921f7',
         'passed':True,'newJavaTests':6,'pythonTests':18,'evidenceHashes':files,
         'sourceHashes':{name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in sources},
         'scope':'Java21/25 focused tests for dynamic v2 edits, restore/replay, identity-independent physics and valid configuration boundary invariants; independent evidence parser unit tests.',
         'limits':['No production engine/default changes; previous 646-test full builds are separate evidence.',
                   '608-case robustness study is separate and not declared complete by these tests.',
                   'No new live server, human, production capacity, provider or official signing acceptance.']}
(target/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({'passed':True,'files':len(files),'sources':len(sources)}))
