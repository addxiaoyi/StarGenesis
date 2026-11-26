from pathlib import Path
import hashlib,json,re
work=Path(__file__).resolve().parent
jobs=json.loads((work/'ci-jobs.json').read_text())
assert all(j['conclusion']=='success' for j in jobs)
result={'passed':True,'commit':'b3ea8523a1366c5c600094b0f02091d932c921f7','runId':45819994176,
        'url':'https://github.com/addxiaoyi/StarGenesis/actions/runs/45819994176','jobs':[]}
for label in ('java21','java25'):
    raw=(work/f'ci-{label}.log').read_text(encoding='utf-8-sig')
    clean=re.sub(r'^\d{4}-\d\d-\d\dT\S+Z ?', '', raw,flags=re.M)
    suites=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: .*? -- in ',clean)
    totals=[sum(int(row[i]) for row in suites) for i in range(4)]
    assert len(suites)==86 and totals==[646,0,0,0],(label,len(suites),totals)
    python=sum(int(n) for n in re.findall(r'^Ran (\d+) tests? in ',clean,re.M))
    assert python==29 and '# tests 6' in clean
    assert clean.count('"passed": true')>=4
    entry={'java':label,'javaTests':646,'suites':86,'pythonTests':python,'nodeTests':6,
           'lockAndSigningProbes':4,'logSha256':hashlib.sha256((work/f'ci-{label}.log').read_bytes()).hexdigest()}
    if label=='java21':
        start=clean.index('{\n  "version": "4.0.0-ci.35"')
        candidate,_=json.JSONDecoder().raw_decode(clean[start:])
        assert candidate['tests']=={'reports':86,'tests':646,'failures':0,'errors':0,'skipped':0}
        assert candidate['profiles']==['helstera-bridge','craftengine-bridge','cookery-bridge']
        assert len(candidate['artifacts'])==4 and not candidate['signed']
        assert candidate['dependencyPreparation']['cookery']['preparation']['tests']=={'tests':28,'failures':0,'errors':0,'skipped':0}
        helstera=candidate['dependencyPreparation']['helstera']['preparation']
        assert helstera['tests']['tests']+sum(row['tests'] for row in helstera['runtimeTests'].values())==330
        (work/'ci-candidate-build.json').write_text(json.dumps(candidate,indent=2)+'\n')
        entry['isolatedCandidate']={'version':candidate['version'],'tests':candidate['tests'],'artifacts':candidate['artifacts'],
                                    'sbom':candidate['sbom'],'sourceTreeSha256':candidate['sourceTreeSha256']}
    result['jobs'].append(entry)
(work/'ci-result.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'passed':True,'runId':result['runId'],'jobs':[{k:v for k,v in j.items() if k!='isolatedCandidate'} for j in result['jobs']]}))
