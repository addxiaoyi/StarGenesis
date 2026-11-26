from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,xml.etree.ElementTree as ET
work=Path(__file__).resolve().parent;root=work.parents[1]
python=str(root/'.work/python-3.12.10/python.exe')
for label,directory in [('java21','jdk-21.0.13.0.2+1'),('java25','jdk-25.0.5.0.2+1')]:
    jdk=root.parent/'.toolchains'/directory;java=jdk/'bin/java.exe'
    env=os.environ.copy();env['JAVA_HOME']=str(jdk);env['PATH']=str(jdk/'bin')+os.pathsep+env.get('PATH','')
    env['MAVEN_OPTS']='-Xms16m -Xmx160m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=16m -XX:ActiveProcessorCount=2'
    command=[str(root/'mvnw.cmd'),'-B','-ntp','-DforkCount=0','-Phelstera-bridge,craftengine-bridge,cookery-bridge','clean','verify']
    with (work/f'verify-{label}.log').open('w',encoding='utf-8') as log:
        subprocess.run(command,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    reports=sorted(root.glob('stargenesis-*/target/surefire-reports/TEST-*.xml'))
    totals={key:sum(int(ET.parse(report).getroot().get(key,'0')) for report in reports) for key in ('tests','failures','errors','skipped')}
    assert totals=={'tests':646,'failures':0,'errors':0,'skipped':0} and len(reports)==86,totals
    artifacts=work/label;artifacts.mkdir()
    jar=artifacts/'StarGenesis-4.0.0-SNAPSHOT.jar';sbom=artifacts/'sbom.json'
    shutil.copyfile(root/'stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar',jar)
    shutil.copyfile(root/'target/sbom.json',sbom)
    for report in reports:
        if 'Settlement' in report.name:shutil.copyfile(report,artifacts/report.name)
    sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
    result={'passed':True,'java':label,'tests':totals,'reports':len(reports),'jarSha256':sha(jar),'sbomSha256':sha(sbom),
            'command':command,'mavenOpts':env['MAVEN_OPTS'],'logSha256':sha(work/f'verify-{label}.log'),
            'scope':'Full three-profile clean verify; cached exact API dependencies from the prior verified RC5 preparation. No provider capability enabled.'}
    (work/f'build-{label}.json').write_text(json.dumps(result,indent=2)+'\n')
    print(label,'646 tests passed',flush=True)
    checks=[('worker-lock',[python,'scripts/test_worker_directory_lock.py','--java-home',str(jdk),'--plugin-jar',str(jar)]),
            ('settlement-lock',[python,'scripts/test_settlement_process_lock.py','--java-home',str(jdk),'--plugin-jar',str(jar)]),
            ('release-signer',[python,'scripts/test_release_artifact_signer.py','--java',str(java),'--plugin-jar',str(jar),'--sbom',str(sbom)]),
            ('design-signer',[python,'scripts/test_signed_design_package_signer.py','--java-home',str(jdk),'--java',str(java),'--plugin-jar',str(jar),'--design','stargenesis-design/src/main/resources/examples/blueprint.json'])]
    for name,command in checks:
        with (work/f'{name}-{label}.log').open('w',encoding='utf-8') as log:
            subprocess.run(command+['--output',str(work/f'{name}-{label}.json')],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
        print(label,name,'passed',flush=True)
