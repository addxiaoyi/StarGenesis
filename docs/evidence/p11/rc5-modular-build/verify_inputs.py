from pathlib import Path
import hashlib,json,shutil,sys,tempfile
work=Path(__file__).resolve().parent;root=work.parents[1]
sys.path.insert(0,str(root/'scripts'))
import build_release_candidate as builder
first=json.loads((work/'first.json').read_text(encoding='utf-8'))
old=root/'.work/release-build-4.0.0-rc.5-20261126T084917Z'
second=root/'.work/release-build-4.0.0-rc.5-20261126T085725Z'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
with tempfile.TemporaryDirectory(prefix='sg-input-check-',dir=work) as temporary:
    copy=Path(temporary)/'source'
    builder.copy_build_inputs(root,copy,first['profiles'])
    # Only the new raw-evidence attribute was added after these two builds.
    original=(old/'.gitattributes').read_bytes()
    assert original==(second/'.gitattributes').read_bytes()
    current=(copy/'.gitattributes').read_text(encoding='utf-8').splitlines()
    expected=original.decode('utf-8').splitlines()
    assert [line for line in current if line!='docs/evidence/p11/rc5-modular-build/** -text -whitespace']==expected
    (copy/'.gitattributes').write_bytes(original)
    assert builder.tree_sha256(copy)==first['sourceTreeSha256']
    hashes={p.relative_to(copy).as_posix():sha(p) for p in sorted(copy.rglob('*')) if p.is_file()}
    # Verify the inputs actually retained in both completed build directories.
    builder.set_reactor_version(copy/'pom.xml',first['version'],True,first['sourceVersion'])
    for module in first['modules']:
        builder.set_reactor_version(copy/module/'pom.xml',first['version'],False,first['sourceVersion'])
    for name in hashes:
        assert sha(copy/name)==sha(old/name)==sha(second/name),name
    result={'passed':True,'sourceTreeSha256':first['sourceTreeSha256'],'files':hashes,
            'scope':'All isolated inputs from both builds match current sources after version rewriting. The original .gitattributes bytes precede the one raw-evidence rule added during archival.'}
    (work/'candidate-inputs.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (work/'source-input-gitattributes.txt').write_bytes(original)
print(json.dumps({'passed':True,'files':len(hashes),'sourceTreeSha256':first['sourceTreeSha256']}))
