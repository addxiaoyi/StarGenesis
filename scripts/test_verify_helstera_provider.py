from pathlib import Path
import tempfile
import unittest
import zipfile
from verify_helstera_provider import verify


class ProviderPayloadTest(unittest.TestCase):
    def fixture(self, root):
        payload = {}
        for module, name in [('helstera-api', 'dev/helstera/api/instance/ModelCreationException.class'),
                             ('helstera-core', 'dev/helstera/core/model/ModelDefinitionImpl.class'),
                             ('helstera-runtime', 'dev/helstera/runtime/instance/InstanceManagerImpl.class'),
                             ('helstera-render-paper', 'dev/helstera/render/display/DisplayRenderer.class')]:
            content = b'requireCreationActive supportsOwnedVisualCleanup' if module == 'helstera-render-paper' else b'fixture'
            target = root/module/'target/classes'/name
            target.parent.mkdir(parents=True)
            target.write_bytes(content)
            payload[name] = content
        jar = root/'provider.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            for name, content in payload.items(): archive.writestr(name, content)
        return jar, payload

    def test_stale_packaged_renderer_is_rejected_even_with_matching_other_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar, payload = self.fixture(root)
            self.assertEqual(4, verify(jar, root)['compiledClasses'])
            (root/'helstera-render-paper/target/classes/dev/helstera/render/display/DisplayRenderer.class').write_bytes(b'new compilation')
            with self.assertRaisesRegex(ValueError, 'stale/different bytecode'):
                verify(jar, root)

    def test_matching_but_unpatched_renderer_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar, payload = self.fixture(root)
            name = 'dev/helstera/render/display/DisplayRenderer.class'
            (root/'helstera-render-paper/target/classes'/name).write_bytes(b'old renderer')
            payload[name] = b'old renderer'
            with zipfile.ZipFile(jar, 'w') as archive:
                for name, content in payload.items(): archive.writestr(name, content)
            with self.assertRaisesRegex(ValueError, 'missing the cleanup/reentry contract'):
                verify(jar, root)


if __name__ == '__main__': unittest.main()
