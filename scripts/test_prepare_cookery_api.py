import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile
from prepare_cookery_api import extract_locked, verify_blob


class CookerySourceTest(unittest.TestCase):
    def test_raw_git_bytes_are_required_and_line_ending_changes_are_rejected(self):
        raw = b'class Recipe {}\r\n'
        expected = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        self.assertEqual(raw, verify_blob(raw, expected))
        for altered in (raw.replace(b'\r\n', b'\n'), raw + b'x', raw[:-1]):
            with self.assertRaises(ValueError): verify_blob(altered, expected)

    def test_archive_traversal_symlinks_and_corruption_cannot_enter_build_sources(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name in ('../escape.java', '/escape.java', 'C:/escape.java', 'safe.java'):
                path=root/'source.zip'
                with zipfile.ZipFile(path,'w') as archive:
                    info=zipfile.ZipInfo('SenLuo_Plugin-rev/'+name)
                    if name=='safe.java': info.external_attr=(0o120777 << 16)
                    archive.writestr(info,b'bad')
                lock={'revision':'rev','files':{name:'0'*40}}
                with self.assertRaises(ValueError): extract_locked(root/'build',lock,archive_path=path)
            self.assertFalse((root/'escape.java').exists())

    def test_unlisted_local_sources_are_not_extracted(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); source=root/'source';source.mkdir()
            raw=b'actual upstream source'
            (source/'locked.java').write_bytes(raw);(source/'extra.java').write_text('extra')
            lock={'files':{'locked.java':hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()}}
            extract_locked(root/'build',lock,source_dir=source)
            self.assertEqual(raw,(root/'build/locked.java').read_bytes())
            self.assertFalse((root/'build/extra.java').exists())


if __name__=='__main__': unittest.main()
