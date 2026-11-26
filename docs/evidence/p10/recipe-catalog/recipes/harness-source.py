"""Run the actual Paper/Cookery metadata probe twice with persisted recipe configuration."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def filesystem_path(path):
    value=str(path.resolve())
    if os.name!='nt' or value.startswith('\\\\?\\'): return Path(value)
    return Path('\\\\?\\UNC\\'+value[2:] if value.startswith('\\\\') else '\\\\?\\'+value)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('java-home','server-jar','provider-jar','cookery-jar','plugin-jar','bridge-jar','libraries-from','provider-data-from','cookery-pack-from','directory'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--port',type=int,default=25687)
    args=parser.parse_args()
    target=args.directory.resolve()
    eula=target/'eula.txt'
    if not eula.is_file() or 'eula=true' not in eula.read_text().lower().splitlines():
        parser.error('An existing accepted eula.txt is required')
    if (target/'plugins').exists() or (target/'result.json').exists():
        parser.error('Use a fresh directory; preserve previous evidence')
    java=args.java_home.resolve()/'bin'/('java.exe' if os.name=='nt' else 'java')
    javac=args.java_home.resolve()/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    libraries=sorted((args.libraries_from.resolve()/'libraries').rglob('*.jar'))
    if not libraries: parser.error('Paper runtime libraries are required')
    artifacts={name:getattr(args,name+'_jar').resolve() for name in ('server','provider','cookery','plugin','bridge')}
    plugins=target/'plugins';plugins.mkdir()
    for name in ('provider','cookery','plugin','bridge'): shutil.copyfile(artifacts[name],plugins/(name+'.jar'))
    provider=plugins/'CraftEngine';provider.mkdir()
    shutil.copytree(args.provider_data_from/'libs',provider/'libs')
    config=(args.provider_data_from/'config.yml').read_text(encoding='utf-8')
    config=config.replace('metrics: true','metrics: false').replace('update-checker: true','update-checker: false')
    for section,following in (('workflows','packs'),('packs','presets'),('presets','self-host')):
        config,count=re.subn(r'(?ms)^  '+section+r':\n.*?(?=^  '+following+r':)','  '+section+': {}\n',config)
        if count!=1: raise ValueError('Unexpected configuration section: '+section)
    config=re.sub(r'(?m)^(\s*send-on-join:) true$',r'\1 false',config)
    (provider/'config.yml').write_text(config,encoding='utf-8')
    pack_source=args.cookery_pack_from.resolve()
    pack_filesystem=filesystem_path(pack_source)
    pack_hashes={p.relative_to(pack_filesystem).as_posix():sha(p) for p in sorted(pack_filesystem.rglob('*')) if p.is_file()}
    if 'pack.yml' not in pack_hashes: raise ValueError('An actual Cookery appliance content pack is required')
    # Keep the fixture folder short on Windows; provider IDs come from pack.yml/configuration.
    shutil.copytree(pack_filesystem,filesystem_path(provider/'resources/kitchen'))
    # The real appliance pack uses the provider's registered vanilla-state mappings.
    internal_source=filesystem_path(args.provider_data_from/'resources/internal')
    internal_hashes={p.relative_to(internal_source).as_posix():sha(p) for p in sorted(internal_source.rglob('*')) if p.is_file()}
    if 'pack.yml' not in internal_hashes: raise ValueError('The provider internal state mapping pack is required')
    shutil.copytree(internal_source,filesystem_path(provider/'resources/internal'))
    pack_hashes.update({'../internal/'+name:value for name,value in internal_hashes.items()})
    (target/'content-provenance.json').write_text(json.dumps({'source':str(pack_source),'files':pack_hashes},indent=2)+'\n',encoding='utf-8')
    pack=provider/'resources/sg_recipe_probe';(pack/'configuration').mkdir(parents=True)
    (pack/'pack.yml').write_text('namespace: stargenesis\nname: StarGenesis isolated recipe probe\nversion: 1\n',encoding='utf-8')
    definitions={'accurate_foods':{'stargenesis:flour':{'require':'minecraft:wheat','result':['minecraft:bread 5','minecraft:cookie 7'],
                    'cook':'millstone','rotations':3,'result_count':2,'lore':['Recipe metadata fixture']}},
                 'bamboo_tray_recipes':{'stargenesis:dry':{'require':'minecraft:kelp','result':'minecraft:dried_kelp','mode':'drying','result_count':1,'time':120}}}
    definition_path=pack/'configuration/recipes.yml'
    definition_path.write_text(json.dumps(definitions,indent=2)+'\n',encoding='utf-8')
    source=Path(__file__).with_name('CookeryRecipeServerProbe.java').resolve()
    classes=target/'fixture-classes';classes.mkdir()
    classpath=os.pathsep.join(str(p) for p in [artifacts['server'],*libraries,artifacts['provider'],artifacts['cookery'],artifacts['plugin']])
    compiled=subprocess.run([str(javac),'-J-Xmx96m','-encoding','UTF-8','--release','21','-cp',classpath,'-d',str(classes),str(source)],capture_output=True,text=True)
    (target/'fixture-compile.log').write_text(compiled.stdout+compiled.stderr,encoding='utf-8');compiled.check_returncode()
    with zipfile.ZipFile(plugins/'probe.jar','w') as archive:
        archive.writestr('plugin.yml','name: CookeryRecipeServerProbe\nversion: 1\nmain: CookeryRecipeServerProbe\napi-version: "1.21.11"\ndepend: [StarGenesisCookery]\n')
        for path in classes.glob('*.class'): archive.write(path,path.name)
    (target/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenable-rcon=false\nview-distance=2\nsimulation-distance=2\nmax-players=8\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nspawn-animals=false\nspawn-monsters=false\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains"}\n',encoding='utf-8')
    runs=[]
    for run in (1,2):
        command=[str(java),'-Xms128m','-Xmx512m','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-XX:ActiveProcessorCount=2',
                 f'-Dstargenesis.probe.run={run}','-cp',os.pathsep.join(str(p) for p in [artifacts['server'],*libraries]),'org.bukkit.craftbukkit.Main','--nogui']
        print('Starting actual Cookery recipe probe',run,flush=True)
        proc=subprocess.Popen(command,cwd=target,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
        output=queue.Queue()
        def drain():
            for line in proc.stdout: output.put(line)
            output.put(None)
        threading.Thread(target=drain,daemon=True).start()
        lines=[];started=time.monotonic();stop_sent=False;log_path=target/f'console-{run}.log'
        try:
            with log_path.open('w',encoding='utf-8') as log:
                while time.monotonic()-started<240:
                    try: line=output.get(timeout=1)
                    except queue.Empty:
                        if proc.poll() is not None: break
                        continue
                    if line is None: break
                    lines.append(line);log.write(line);log.flush()
                    if not stop_sent and any(marker in line for marker in ('COOKERY_RECIPE_PROBE_PASSED','COOKERY_RECIPE_PROBE_FAILED','Error occurred while enabling')):
                        proc.stdin.write('stop\n');proc.stdin.flush();stop_sent=True
                if proc.poll() is None and not stop_sent: proc.stdin.write('stop\n');proc.stdin.flush()
            proc.wait(timeout=50)
        finally:
            if proc.poll() is None: proc.kill();proc.wait(timeout=10)
        fixture=plugins/'CookeryRecipeServerProbe'/f'result-{run}.json'
        observed=json.loads(fixture.read_text(encoding='utf-8')) if fixture.exists() else {'passed':False}
        errors=[line.strip() for line in lines if 'ERROR' in line or 'SEVERE' in line]
        passed=(observed['passed'] and proc.returncode==0 and not errors and 'COOKERY_RECIPE_PROBE_PASSED' in ''.join(lines)
                and 'StarGenesis stopped cleanly' in ''.join(lines))
        runs.append({'passed':passed,'run':run,'seconds':round(time.monotonic()-started,2),'exitCode':proc.returncode,'fixture':observed,
                     'errors':errors,'logSha256':sha(log_path),'configurationSha256':sha(definition_path)})
        result={'passed':all(r['passed'] for r in runs) and len(runs)==2,'runs':runs,'sha256':{name:sha(path) for name,path in artifacts.items()},
                'fixtureSourceSha256':sha(source),'providerConfigSha256':sha(provider/'config.yml'),'contentProvenanceSha256':sha(target/'content-provenance.json'),
                'scope':'Actual Paper/Cookery metadata, resource reload, shutdown, two JVM starts, and synthetic command senders. No real player, inventory delivery, cooking or rendered visual acceptance.'}
        (target/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        if not passed: raise SystemExit(json.dumps(result))
    print(json.dumps(result))


if __name__=='__main__': main()
