from pathlib import Path
import json, shutil, subprocess, sys
work = Path(__file__).resolve().parent
root = work.parents[1]
python = str(root/'.work/python-3.12.10/python.exe')
jdk = root.parent/'.toolchains/jdk-21.0.13.0.2+1'
artifacts = work/'artifacts-java21-final'
for mode in sys.argv[1:] or ['restart','client','helstera','folia']:
    target = work/('final-'+mode)
    target.mkdir()
    shutil.copyfile(root/f'.work/rc4-live/{"folia" if mode == "folia" else "paper"}/eula.txt',target/'eula.txt')
    if mode in ('restart','client'):
        command = [python,'scripts/server_craftengine_item_smoke.py','--java-home',str(jdk),
                   '--server-jar',str(root/'.work/rc4-live/paper/versions/1.21.11/paper-1.21.11.jar'),
                   '--provider-jar',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/artifacts/plugins/craftengine-26.9.1.jar'),
                   '--plugin-jar',str(artifacts/'stargenesis-paper.jar'),'--bridge-jar',str(artifacts/'stargenesis-craftengine.jar'),
                   '--libraries-from',str(root/'.work/rc4-live/paper'),
                   '--provider-data-from',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/paper/plugins/CraftEngine'),
                   '--directory',str(target)]
        if mode == 'client':
            command += ['--clients','--runs','1','--node',r'C:\Program Files\nodejs\node.exe','--node-modules',str(root.parent/'SenLuo_Plugin-continue/build/network-client/node_modules')]
        result_path = target/'result.json'
    elif mode == 'helstera':
        command = [python,'scripts/server_helstera_catalog_smoke.py','--probe','projection','--java-home',str(jdk),
                   '--server-jar',str(root/'.work/rc4-live/paper/versions/1.21.11/paper-1.21.11.jar'),
                   '--provider-jar',str(root/'.work/helstera-projection/upstream/helstera-plugin/target/helsteraMobs-1.0.0-full.jar'),
                   '--plugin-jar',str(artifacts/'stargenesis-paper.jar'),'--bridge-jar',str(artifacts/'stargenesis-helstera.jar'),
                   '--libraries-from',str(root/'.work/rc4-live/paper'),'--directory',str(target),
                   '--texture',str(root/'.work/helstera-bridge/paper-final/plugins/HelsteraCatalogServerProbe/model/texture.png')]
        result_path = target/'result.json'
    else:
        command = [python,'scripts/server_smoke.py','--java',str(jdk/'bin/java.exe'),
                   '--server-jar',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/runtime/folia-1.21.11-14.jar'),
                   '--plugin-jar',str(artifacts/'stargenesis-paper.jar'),'--libraries-from',str(root/'.work/rc4-live/folia'),
                   '--directory',str(target),'--kind','folia','--port','25684','--heap-mb','512',
                   '--jvm-arg=-XX:+UseSerialGC','--jvm-arg=-XX:TieredStopAtLevel=1','--jvm-arg=-XX:ReservedCodeCacheSize=64m',
                   '--jvm-arg=-XX:ActiveProcessorCount=2','--timeout','180']
        result_path = target/'smoke-result.json'
    with (work/f'final-{mode}-launch.log').open('w',encoding='utf-8') as log:
        subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
    assert json.loads(result_path.read_text(encoding='utf-8'))['passed']
    print(mode,'passed',flush=True)
