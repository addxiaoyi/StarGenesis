from pathlib import Path
import hashlib,json,subprocess
work=Path(__file__).resolve().parent
repo=Path('C:/Users/Administrator/Documents/StarChronicle-audit/starcore-all-stages')
revision='7850efac96ce0721979c370d0ca8d4f4765c0935'
baseline='f769e62799e769a18fe51305b545869a17f80cd1'
paths=['src/main/java/dev/starcore/starcore/api/StarCoreApi.java',
       'src/main/java/dev/starcore/starcore/foundation/economy/EconomyService.java',
       'src/main/java/dev/starcore/starcore/module/resource/NationResourceTransactionService.java',
       'src/main/java/dev/starcore/starcore/module/resource/TradeSettlementCoordinator.java',
       'src/main/java/dev/starcore/starcore/module/resource/model/TradeSettlementIntent.java']
target=work/'starcore-audit';target.mkdir(exist_ok=True)
files={}
for index,path in enumerate(paths):
    content=subprocess.check_output(['git','show',revision+':'+path],cwd=repo)
    blob=hashlib.sha1(b'blob '+str(len(content)).encode()+b'\0'+content).hexdigest()
    assert blob==subprocess.check_output(['git','rev-parse',revision+':'+path],cwd=repo).decode().strip()
    name=Path(path).name+'.txt';(target/name).write_bytes(content)
    files[path]={'file':name,'gitBlob':blob,'sha256':hashlib.sha256(content).hexdigest()}
    if index<2:assert content==subprocess.check_output(['git','show',baseline+':'+path],cwd=repo)
summary={'main':baseline,'development':revision,'source':'Read-only exact Git objects; GitHub API verified branch heads and source tree',
         'files':files,'scope':'No upstream checkout modified. Source audit only, no runtime or external settlement proof.'}
(target/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'files':len(files),'unchangedCoreApiFiles':2}))
