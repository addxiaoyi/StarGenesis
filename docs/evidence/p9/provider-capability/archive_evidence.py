from pathlib import Path
import hashlib,json,shutil
work=Path(__file__).resolve().parent;root=work.parents[1]
target=root/'docs/evidence/p9/provider-capability';target.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
files={}
def copy(source,name):
    output=target/name;output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists():assert sha(output)==sha(source),name
    else:shutil.copyfile(source,output)
    files[name]=sha(output)
builds={}
for label in ('java21','java25'):
    build=json.loads((work/f'build-{label}.json').read_text());assert build['passed'];builds[label]=build
    for name in (f'build-{label}.json',f'verify-{label}.log'):copy(work/name,name)
    for source in (work/label).glob('TEST-*.xml'):copy(source,label+'/'+source.name)
    copy(work/label/'sbom.json',label+'/sbom.json')
    for name in ('worker-lock','settlement-lock','release-signer','design-signer'):
        assert json.loads((work/f'{name}-{label}.json').read_text()).get('passed',True)
        for ext in ('json','log'):copy(work/f'{name}-{label}.{ext}',f'probes/{name}-{label}.{ext}')
for name in ('before.log','before-regression.log','focused.log','settlement-tests.log','settlement-final.log',
             'builder-regression.log','verify_all.py','archive_evidence.py','check_evidence.py','save_starcore_audit.py'):
    copy(work/name,name)
for source in (work/'starcore-audit').iterdir():
    if source.is_file():copy(source,'starcore-audit/'+source.name)
sources=['stargenesis-integrations/src/main/java/dev/stargenesis/integrations/'+name+'.java' for name in
         ('IdempotentSettlementPort','SettlementRecoveryGuard','SettlementSagaCoordinator','SettlementReviewCoordinator')]
sources+=['stargenesis-integrations/src/test/java/dev/stargenesis/integrations/SettlementRecoveryLifecycleTest.java',
          'stargenesis-paper/src/test/java/dev/stargenesis/paper/settlement/SettlementReviewRecoveryTest.java',
          'stargenesis-paper/src/main/java/dev/stargenesis/paper/settlement/FileSettlementSagaRepository.java',
          '.github/workflows/verify.yml']
summary={'date':'2026-11-26','baseCommit':'f13bf7af4bf7ec51cd8b79f6b699a0af4650f995',
         'scope':'Check current recovery capability around settlement authorization, provider calls and local commits; preserve prior state when capability is withdrawn or audit evidence changes',
         'newJavaTests':12,'focusedTests':62,'builds':builds,'sourceHashes':{name:sha(root/name) for name in sources},
         'evidenceHashes':files,'before':{'tests':9,'failures':7,'errors':0,'skipped':0},
         'failureNotes':{'before.log':'Initial test fixture referenced nonexistent operation.phase(); fixture corrected before reproduction',
                         'before-regression.log':'Seven genuine assertion failures in nine new tests against unchanged production coordinators',
                         'settlement-tests.log':'PowerShell split unquoted -Dsurefire.failIfNoSpecifiedTests=false; no tests executed; quoted rerun passed'},
         'limits':['Provider is a mutable in-memory unit fixture; real repository byte preservation and reopen are tested',
                   'Capability evidence is not a persistent authority binding; new coordinator creation does not validate another ledger history',
                   'Before/after checks do not replace provider operation leases or detect all concurrent ABA changes',
                   'No real payment, inventory transfer, runtime provider or server acceptance; P9 production entry remains disabled',
                   'Published local RC5 hashes do not contain this later SNAPSHOT fix',
                   'CI candidate versions now use workflow run numbers instead of reusing RC5']}
(target/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'passed':True,'evidenceFiles':len(files),'sourceFiles':len(sources)}))
