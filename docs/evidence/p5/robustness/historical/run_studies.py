from pathlib import Path
import hashlib,json,subprocess,os,traceback

work=Path(__file__).resolve().parent
root=work.parents[1]
jar=root/'.work/settlement-capability/java21/StarGenesis-4.0.0-SNAPSHOT.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='63de78696d735ba45fa88f05f6a72b4c57317950c07876098878731ec73528c2'
state=work/'process.json'
for label,jdk,baseline,paired in [('java21','jdk-21.0.13.0.2+1',64,32),('java25','jdk-25.0.5.0.2+1',4,4)]:
    command=[str(root.parent/'.toolchains'/jdk/'bin/java.exe'),'-Xms16m','-Xmx96m','-Xss512k','-XX:+UseSerialGC',
             '-XX:CICompilerCount=2','-XX:ReservedCodeCacheSize=64m','-XX:MaxMetaspaceSize=96m',
             '-XX:ActiveProcessorCount=2','-cp',str(jar),'scripts/EcologyRobustnessStudy.java',str(work/f'{label}.json'),str(baseline),str(paired)]
    if (work/f'{label}.json').exists():command.append('--resume')
    (work/f'command-{label}.json').write_text(json.dumps(command,indent=2)+'\n')
    with (work/f'{label}.log').open('a',encoding='utf-8') as log:
        child=subprocess.Popen(command,cwd=root,stdout=log,stderr=subprocess.STDOUT)
        state.write_text(json.dumps({'status':'running','java':label,'launcherPid':os.getpid(),'childPid':child.pid,'command':command},indent=2)+'\n')
        code=child.wait()
        if code:
            state.write_text(json.dumps({'status':'failed','java':label,'code':code},indent=2)+'\n')
            raise SystemExit(code)
    print(label,'study complete',flush=True)
state.write_text(json.dumps({'status':'complete'},indent=2)+'\n')
