from pathlib import Path
import hashlib, json, zipfile

work = Path(__file__).resolve().parent
root = work.parents[1]
runtime = root.parent/'SenLuo_Plugin-continue/build/acceptance/v612/artifacts/plugins/craftengine-26.9.1.jar'
lock = json.loads((root/'scripts/craftengine-api-lock.json').read_text())
digest = lambda data: hashlib.sha256(data).hexdigest()
assert digest(runtime.read_bytes()) == '021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f'
result = {'runtimePath': str(runtime.relative_to(root.parent)), 'runtimeSha256': digest(runtime.read_bytes()),
          'apiLockSha256': digest((root/'scripts/craftengine-api-lock.json').read_bytes()), 'modules': {}}
critical = ['net/momirealms/craftengine/core/plugin/ResourceOperationCoordinator',
            'net/momirealms/craftengine/core/plugin/ResourceOperationCoordinator$Lease',
            'net/momirealms/craftengine/core/plugin/ResourceOperationCoordinator$BusyException',
            'net/momirealms/craftengine/bukkit/api/CraftEngineItems',
            'net/momirealms/craftengine/bukkit/plugin/BukkitCraftEngine',
            'net/momirealms/craftengine/bukkit/item/factory/ComponentItemFactory1_20_5']
compared = {}
with zipfile.ZipFile(runtime) as provider:
    result['descriptor'] = provider.read('paper-plugin.yml').decode()
    for module in ('core','bukkit'):
        name = f'craft-engine-{module}-26.9.1.jar'
        path = work/'deps'/name
        assert digest(path.read_bytes()) == lock['files'][name]['sha256']
        with zipfile.ZipFile(path) as api:
            same, changed, missing = [], {}, []
            for name in api.namelist():
                if not name.startswith('net/momirealms/craftengine/') or not name.endswith('.class'): continue
                if name not in provider.namelist(): missing.append(name); continue
                a, b = digest(api.read(name)), digest(provider.read(name))
                compared[name] = (a,b)
                if a == b: same.append(name)
                else: changed[name] = {'officialApi': a, 'runtime': b}
            result['modules'][module] = {'identicalClassCount': len(same), 'changedClasses': changed, 'missingClasses': missing}
    result['criticalIdenticalClasses'] = {}
    for name in critical:
        a,b=compared[name+'.class']
        assert a == b, name
        result['criticalIdenticalClasses'][name] = a
result['scope'] = 'Exact local provider bytes compared with official locked API JAR classes. Changed classes are disclosed, not treated as identical official builds. Actual reload/creation/restoration tests establish only the recorded Paper runtime behavior.'
(work/'provider-provenance.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:{'identical':v['identicalClassCount'],'changed':len(v['changedClasses']),'missing':len(v['missingClasses'])} for k,v in result['modules'].items()}))
