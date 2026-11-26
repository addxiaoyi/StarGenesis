from pathlib import Path
import json,shutil,subprocess,sys
work=Path(__file__).resolve().parent;root=work.parents[1]
python=str(root/'.work/python-3.12.10/python.exe');jdk=root.parent/'.toolchains/jdk-21.0.13.0.2+1'
artifacts=work/'first'
base=artifacts/'StarGenesis-4.0.0-rc.5.jar'
for mode in sys.argv[1:] or ['cookery','item','helstera','folia']:
    target=work/('server-'+mode);target.mkdir()
    shutil.copyfile(root/f'.work/rc4-live/{"folia" if mode=="folia" else "paper"}/eula.txt',target/'eula.txt')
    common=['--java-home',str(jdk),'--server-jar',str(root/'.work/rc4-live/paper/versions/1.21.11/paper-1.21.11.jar'),
            '--plugin-jar',str(base),'--libraries-from',str(root/'.work/rc4-live/paper'),'--directory',str(target)]
    ce=['--provider-jar',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/artifacts/plugins/craftengine-26.9.1.jar'),
        '--provider-data-from',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/paper/plugins/CraftEngine')]
    if mode=='cookery':
        command=[python,'scripts/server_cookery_recipe_smoke.py',*common,*ce,
                 '--cookery-jar',str(root/'.work/senluo-bridge/upstream/build/libs/versions/KaleidoscopeCookeryPlugin-1.3.0-mc1.21.11.jar'),
                 '--bridge-jar',str(artifacts/'StarGenesisCookery-4.0.0-rc.5.jar'),
                 '--cookery-pack-from',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/paper/plugins/CraftEngine/resources/cookery_kitchen')]
    elif mode=='item':
        command=[python,'scripts/server_craftengine_item_smoke.py',*common,*ce,'--bridge-jar',str(artifacts/'StarGenesisCraftEngine-4.0.0-rc.5.jar')]
    elif mode=='helstera':
        command=[python,'scripts/server_helstera_catalog_smoke.py',*common,'--probe','projection',
                 '--provider-jar',str(root/'.work/helstera-projection/upstream/helstera-plugin/target/helsteraMobs-1.0.0-full.jar'),
                 '--bridge-jar',str(artifacts/'StarGenesisHelstera-4.0.0-rc.5.jar'),
                 '--texture',str(root/'.work/helstera-bridge/paper-final/plugins/HelsteraCatalogServerProbe/model/texture.png')]
    else:
        command=[python,'scripts/server_smoke.py','--java',str(jdk/'bin/java.exe'),
                 '--server-jar',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/runtime/folia-1.21.11-14.jar'),
                 '--plugin-jar',str(base),'--libraries-from',str(root/'.work/rc4-live/folia'),'--directory',str(target),'--kind','folia',
                 '--port','25684','--heap-mb','512','--jvm-arg=-XX:+UseSerialGC','--jvm-arg=-XX:TieredStopAtLevel=1',
                 '--jvm-arg=-XX:ReservedCodeCacheSize=64m','--jvm-arg=-XX:ActiveProcessorCount=2','--timeout','180']
    with (work/f'server-{mode}-launch.log').open('w',encoding='utf-8') as log:
        subprocess.run(command,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
    result=target/('smoke-result.json' if mode=='folia' else 'result.json')
    assert json.loads(result.read_text(encoding='utf-8'))['passed']
    print(mode,'passed',flush=True)
