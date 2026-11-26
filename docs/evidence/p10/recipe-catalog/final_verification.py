from pathlib import Path
import hashlib, json, os, shutil, subprocess
import xml.etree.ElementTree as ET

work = Path(__file__).resolve().parent
root = work.parents[1]
python = str(root/'.work/python-3.12.10/python.exe')
modules = ('stargenesis-paper','stargenesis-helstera','stargenesis-craftengine','stargenesis-cookery')
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
for label, directory in (('java21','jdk-21.0.13.0.2+1'), ('java25','jdk-25.0.5.0.2+1')):
    jdk = root.parent/'.toolchains'/directory
    os.environ['JAVA_HOME'] = str(jdk)
    os.environ['PATH'] = str(jdk/'bin') + os.pathsep + os.environ['PATH']
    os.environ['MAVEN_OPTS'] = ('-Xms16m -Xmx160m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 '
                              '-XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=128m '
                              '-XX:CompressedClassSpaceSize=16m -XX:ActiveProcessorCount=2')
    if label == 'java25':
        with (work/'portable-api-java25.log').open('w',encoding='utf-8') as log:
            subprocess.run([python,'scripts/prepare_cookery_api.py','--source-dir',str(work/'upstream'),'--work-dir',str(work/'portable-api-java25'),'--offline'],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
    command = [str(root/'mvnw.cmd'),'-B','-ntp','-Phelstera-bridge,craftengine-bridge,cookery-bridge','-DforkCount=0','clean','verify']
    log_path = work/f'build-{label}-final.log'
    with log_path.open('w',encoding='utf-8') as log:
        subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
    reports = sorted(root.glob('*/target/surefire-reports/TEST-*.xml'))
    counts = {k:sum(int(ET.parse(p).getroot().attrib[k]) for p in reports) for k in ('tests','failures','errors','skipped')}
    assert counts == {'tests':634,'failures':0,'errors':0,'skipped':0}, counts
    artifact_dir = work/f'artifacts-{label}-final'
    artifact_dir.mkdir()
    for module in modules:
        shutil.copyfile(root/module/'target'/(module+'-4.0.0-SNAPSHOT.jar'),artifact_dir/(module+'.jar'))
    shutil.copyfile(root/'target/sbom.json',artifact_dir/'sbom.json')
    summary = {'tests':counts,'suites':len(reports),'javaHome':str(jdk),'mavenOpts':os.environ['MAVEN_OPTS'],
               'command':command,'sha256':{str(p.relative_to(root)):sha(p) for p in [*artifact_dir.iterdir(),log_path]},
               'suitesSummary':{ET.parse(p).getroot().attrib['name']:int(ET.parse(p).getroot().attrib['tests']) for p in reports}}
    (work/f'build-{label}-final.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
    print(label, counts, flush=True)
    java = str(jdk/'bin/java.exe')
    jar = str(artifact_dir/'stargenesis-paper.jar')
    checks = [
        ('worker-lock',[python,'scripts/test_worker_directory_lock.py','--java-home',str(jdk),'--plugin-jar',jar]),
        ('settlement-lock',[python,'scripts/test_settlement_process_lock.py','--java-home',str(jdk),'--plugin-jar',jar]),
        ('release-signer',[python,'scripts/test_release_artifact_signer.py','--java',java,'--plugin-jar',jar,'--sbom',str(artifact_dir/'sbom.json')]),
        ('design-signer',[python,'scripts/test_signed_design_package_signer.py','--java-home',str(jdk),'--java',java,'--plugin-jar',jar,'--design','stargenesis-design/src/main/resources/examples/blueprint.json'])]
    for name, args in checks:
        output = work/f'{name}-{label}.json'
        with (work/f'{name}-{label}.log').open('w',encoding='utf-8') as log:
            subprocess.run(args+['--output',str(output)],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
        assert json.loads(output.read_text(encoding='utf-8')).get('passed',True)
        print(label,name,'passed',flush=True)
