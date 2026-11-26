from pathlib import Path
import hashlib,json,os,subprocess,sys,shutil
work=Path(__file__).resolve().parent;root=work.parents[1];python=str(root/'.work/python-3.12.10/python.exe')
jar=work/'first/StarGenesis-4.0.0-rc.5.jar';sbom=work/'first/sbom.json'
for label,directory in [('java21','jdk-21.0.13.0.2+1'),('java25','jdk-25.0.5.0.2+1')]:
    jdk=root.parent/'.toolchains'/directory;java=str(jdk/'bin/java.exe')
    checks=[('worker-lock',[python,'scripts/test_worker_directory_lock.py','--java-home',str(jdk),'--plugin-jar',str(jar)]),
            ('settlement-lock',[python,'scripts/test_settlement_process_lock.py','--java-home',str(jdk),'--plugin-jar',str(jar)]),
            ('release-signer',[python,'scripts/test_release_artifact_signer.py','--java',java,'--plugin-jar',str(jar),'--sbom',str(sbom)]),
            ('design-signer',[python,'scripts/test_signed_design_package_signer.py','--java-home',str(jdk),'--java',java,'--plugin-jar',str(jar),'--design','stargenesis-design/src/main/resources/examples/blueprint.json'])]
    for name,command in checks:
        with (work/f'{name}-{label}.log').open('w',encoding='utf-8') as log:
            subprocess.run(command+['--output',str(work/f'{name}-{label}.json')],cwd=root,stdout=log,stderr=subprocess.STDOUT,check=True)
        print(label,name,'passed',flush=True)
