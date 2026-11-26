import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from prepare_helstera_api import extract_locked, verified_patch
import json


class SourceLockTest(unittest.TestCase):
    def test_only_exact_locked_sources_are_extracted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = b'actual upstream source'
            expected = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
            lock = {'revision': 'fixed', 'files': {'pom.xml': expected}}
            archive = root / 'source.zip'
            with zipfile.ZipFile(archive, 'w') as out:
                out.writestr('helsteraMobs-fixed/pom.xml', data)
                out.writestr('helsteraMobs-fixed/unlocked.java', b'untrusted')
            extract_locked(archive, root / 'source', lock)
            self.assertEqual(data, (root / 'source/pom.xml').read_bytes())
            self.assertFalse((root / 'source/unlocked.java').exists())
            lock['files']['pom.xml'] = '0' * 40
            with self.assertRaisesRegex(ValueError, 'blob mismatch'):
                extract_locked(archive, root / 'changed', lock)

    def test_unsafe_lock_path_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / 'source.zip', 'w'):
                pass
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                extract_locked(root / 'source.zip', root / 'source',
                               {'revision': 'fixed', 'files': {'../escape.java': '0' * 40}})

    def test_checked_in_lifecycle_patch_is_pinned_and_tampering_is_rejected(self):
        lock = json.loads(Path(__file__).with_name('helstera-api-lock.json').read_text())
        self.assertTrue(verified_patch(lock).is_file())
        lock['patch']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'patch hash/path mismatch'):
            verified_patch(lock)
        lock['patch']['file'] = '../../pom.xml'
        with self.assertRaisesRegex(ValueError, 'patch hash/path mismatch'):
            verified_patch(lock)


if __name__ == '__main__':
    unittest.main()
