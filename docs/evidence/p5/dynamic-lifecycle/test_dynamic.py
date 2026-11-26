from pathlib import Path
import json,os,subprocess,sys,shutil,xml.etree.ElementTree as ET
work=Path(__file__).resolve().parent;root=work.parents[1]
attempt=sys.argv[1]
for label,jdk in [('java21','jdk-21.0.13.0.2+1'),('java25','jdk-25.0.5.0.2+1')]:
    directory=root.parent/'.toolchains'/jdk
    env=os.environ.copy();env['JAVA_HOME']=str(directory);env['PATH']=str(directory/'bin')+os.pathsep+env['PATH']
    env['MAVEN_OPTS']='-Xms16m -Xmx160m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=128m -XX:CompressedClassSpaceSize=16m -XX:ActiveProcessorCount=2'
    command=[str(root/'mvnw.cmd'),'-B','-ntp','-pl','stargenesis-sim','-am','-DforkCount=0',
             '-Dtest=EcologyDynamicLifecycleV2Test','-Dsurefire.failIfNoSpecifiedTests=false','test']
    log=work/f'dynamic-{label}-{attempt}.log'
    with log.open('x',encoding='utf-8') as stream:
        subprocess.run(command,cwd=root,env=env,stdout=stream,stderr=subprocess.STDOUT,check=True)
    report=root/'stargenesis-sim/target/surefire-reports/TEST-dev.stargenesis.sim.EcologyDynamicLifecycleV2Test.xml'
    result={key:int(ET.parse(report).getroot().get(key,'0')) for key in ('tests','failures','errors','skipped')}
    assert result=={'tests':6,'failures':0,'errors':0,'skipped':0},result
    shutil.copyfile(report,work/f'dynamic-{label}-{attempt}.xml')
    (work/f'dynamic-{label}-{attempt}.json').write_text(json.dumps({'passed':True,'java':label,'tests':result,'command':command},indent=2)+'\n')
    print(label,result,flush=True)
