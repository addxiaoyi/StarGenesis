"""Reject a provider JAR whose patched runtime bytecode differs from the compiled modules."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def verify(provider, upstream):
    classes = {}
    # These three pre-existing classes are rewritten by the provider's exp4j relocation.
    # Record both hashes, but make no byte-equality claim about them.
    relocated_names = {f'dev/helstera/api/util/NumericExpression{suffix}.class' for suffix in ('', '$1', '$2')}
    relocated = {}
    with zipfile.ZipFile(provider) as jar:
        for module in ('helstera-api', 'helstera-core', 'helstera-runtime', 'helstera-render-paper'):
            root = upstream / module / 'target/classes'
            paths = sorted(root.rglob('*.class'))
            if not paths:
                raise ValueError(f'No compiled classes for {module}')
            for path in paths:
                name = path.relative_to(root).as_posix()
                content = path.read_bytes()
                if name in relocated_names:
                    relocated[name] = {'compiledSha256': hashlib.sha256(content).hexdigest(),
                                       'providerSha256': hashlib.sha256(jar.read(name)).hexdigest()}
                    continue
                if jar.read(name) != content:
                    raise ValueError(f'Provider contains stale/different bytecode: {module}/{name}')
                classes[name] = hashlib.sha256(content).hexdigest()
        renderer = jar.read('dev/helstera/render/display/DisplayRenderer.class')
        if b'requireCreationActive' not in renderer or b'supportsOwnedVisualCleanup' not in renderer:
            raise ValueError('Provider is missing the cleanup/reentry contract')
    return {'passed': True, 'providerSha256': hashlib.sha256(provider.read_bytes()).hexdigest(),
            'compiledClasses': len(classes), 'classes': classes,
            'excludedExp4jRelocations': relocated,
            'scope': 'API/core/runtime/renderer class byte equality except three named exp4j relocation classes; not full upstream verification'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider-jar', type=Path, required=True)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.provider_jar, args.upstream)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({key:value for key,value in result.items() if key != 'classes'}))


if __name__ == '__main__':
    main()
