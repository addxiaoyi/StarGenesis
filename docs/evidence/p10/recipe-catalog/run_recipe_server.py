from pathlib import Path
import json,shutil,subprocess,sys
work=Path(__file__).resolve().parent
root=work.parents[1]
python=str(root/'.work/python-3.12.10/python.exe')
label=sys.argv[1] if len(sys.argv)>1 else 'paper-first'
target=work/label
target.mkdir()
shutil.copyfile(root/'.work/rc4-live/paper/eula.txt',target/'eula.txt')
artifacts=work/'artifacts-java21-final'
args=[python,'scripts/server_cookery_recipe_smoke.py','--java-home',str(root.parent/'.toolchains/jdk-21.0.13.0.2+1'),
      '--server-jar',str(root/'.work/rc4-live/paper/versions/1.21.11/paper-1.21.11.jar'),
      '--provider-jar',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/artifacts/plugins/craftengine-26.9.1.jar'),
      '--cookery-jar',str(work/'upstream/build/libs/versions/KaleidoscopeCookeryPlugin-1.3.0-mc1.21.11.jar'),
      '--plugin-jar',str(artifacts/'stargenesis-paper.jar'),'--bridge-jar',str(artifacts/'stargenesis-cookery.jar'),
      '--libraries-from',str(root/'.work/rc4-live/paper'),
      '--provider-data-from',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/paper/plugins/CraftEngine'),
      '--cookery-pack-from',str(root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/paper/plugins/CraftEngine/resources/cookery_kitchen'),
      '--directory',str(target)]
with (work/(label+'-launch.log')).open('w',encoding='utf-8') as log:
    subprocess.run(args,cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
assert json.loads((target/'result.json').read_text(encoding='utf-8'))['passed']
print('Actual Paper recipe probe passed',flush=True)
