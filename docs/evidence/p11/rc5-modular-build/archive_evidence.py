from pathlib import Path
import hashlib,json,shutil,subprocess
work=Path(__file__).resolve().parent;root=work.parents[1];target=root/'docs/evidence/p11/rc5-modular-build';target.mkdir(parents=True,exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
files={}
def copy(source,name):
    output=target/name;output.parent.mkdir(parents=True,exist_ok=True)
    if output.exists() and sha(output)!=sha(source):raise ValueError('Evidence conflict: '+str(output))
    if not output.exists():shutil.copyfile(source,output)
    files[name]=sha(output)
repro=json.loads((work/'reproducibility.json').read_text());assert repro['passed']
for label in ('first','second'):
    copy(work/(label+'.json'),label+'/build.json');copy(work/(label+'-launch.log'),label+'/launch.log')
    for source in (work/label).rglob('*'):
        if source.is_file() and source.suffix in ('.json','.log','.xml'):copy(source,label+'/'+source.relative_to(work/label).as_posix())
for label in ('java21','java25'):
    for name in ('worker-lock','settlement-lock','release-signer','design-signer'):
        assert json.loads((work/f'{name}-{label}.json').read_text()).get('passed',True)
        for ext in ('json','log'):copy(work/f'{name}-{label}.{ext}',f'probes/{name}-{label}.{ext}')
    assert json.loads((work/f'optional-manifest-{label}.json').read_text())['passed']
    copy(work/f'optional-manifest-{label}.json',f'probes/optional-manifest-{label}.json')
servers={}
for mode in ('cookery','item','helstera','folia'):
    folder=work/('server-'+mode);result_name='smoke-result.json' if mode=='folia' else 'result.json'
    result=json.loads((folder/result_name).read_text(encoding='utf-8'));assert result['passed'];servers[mode]=result
    copy(folder/result_name,f'servers/{mode}/{result_name}')
    for source in folder.glob('*.log'):copy(source,f'servers/{mode}/{source.name}')
    if (folder/'content-provenance.json').exists():copy(folder/'content-provenance.json',f'servers/{mode}/content-provenance.json')
    copy(work/f'server-{mode}-launch.log',f'servers/{mode}/launch.log')
    for subfolder in (folder/'plugins').glob('*Probe'):
        for source in subfolder.glob('*.json'):copy(source,f'servers/{mode}/fixture/{source.name}')
for name in ('builder-before.log','builder-final.log','python-tests.log','reproducibility.json','bytecode-comparison.json',
             'candidate-inputs.json','source-input-gitattributes.txt','verify_inputs.py','check_evidence.py',
             'verify_candidate.py','run_checks.py','run_servers.py','optional_manifest_probe.py','archive_evidence.py'):
    copy(work/name,name)
source_names=['scripts/build_release_candidate.py','scripts/test_release_candidate_builder.py','.github/workflows/verify.yml',
              'scripts/prepare_helstera_api.py','scripts/prepare_craftengine_api.py','scripts/prepare_cookery_api.py',
              'scripts/cookery-api.init.gradle','scripts/helstera-api-lock.json','scripts/craftengine-api-lock.json','scripts/cookery-api-lock.json',
              'scripts/server_cookery_recipe_smoke.py','scripts/CookeryRecipeServerProbe.java','scripts/server_craftengine_item_smoke.py',
              'scripts/CraftEngineItemServerProbe.java','scripts/server_helstera_catalog_smoke.py','scripts/HelsteraProjectionServerProbe.java','scripts/server_smoke.py',
              'scripts/test_worker_directory_lock.py','scripts/test_settlement_process_lock.py','scripts/test_release_artifact_signer.py',
              'scripts/test_signed_design_package_signer.py','scripts/ReleaseArtifactSigner.java','scripts/SignedDesignPackageSigner.java']
summary={'date':'2026-11-26','baseCommit':'db15f3741637be688eaecf6f12071ee2c017cd09','version':'4.0.0-rc.5',
         'scope':'Reproducible isolated candidate with three explicitly selected optional bridges, verified real dependency preparations and separate plugin JARs; unsigned',
         'reproducibility':repro,'candidateInputFiles':309,'sourceHashes':{name:sha(root/name) for name in source_names},'evidenceHashes':files,'serverResults':servers,
         'pythonTests':29,'builderTests':8,
         'failuresPreserved':{'builder-before.log':'Three new profile/isolation/report regression cases were unsupported by the original builder signatures and raised TypeError; no claim of an old actual RC build failure'},
         'limits':['Local reproducibility compares two Windows Java21 builds; Linux CI is separate evidence',
                   'JAR bytecode unchanged from db15f37; 1408 classes compared, version/build resources differ',
                   'Original build .gitattributes archived; the raw-evidence protection rule was added afterward; all 309 actual inputs rechecked',
                   'Actual provider artifacts retain the prior documented provenance and platform limits',
                   'Folia checks base plugin only; optional bridges remain Paper-only',
                   'No real-player acceptance, resource-pack rendered review, production capacity or official release signature',
                   'Temporary-key signing tests do not install operational trust roots or authorize production settlement']}
(target/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'evidenceFiles':len(files),'sourceFiles':len(source_names),'passed':True}))
