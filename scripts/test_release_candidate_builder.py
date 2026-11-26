"""Regression tests for isolated release input and test/evidence rejection boundaries."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import build_release_candidate as builder


class ReleaseCandidateBuilderTest(unittest.TestCase):
    def test_missing_wrong_version_and_bundled_provider_jars_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar=Path(temporary)/'plugin.jar'
            with self.assertRaises(RuntimeError):builder.verified_plugin(jar,'4.0.0-rc.5','stargenesis-cookery')
            for version,classes in [('4.0.0-SNAPSHOT',['dev/stargenesis/cookery/Bridge.class']),
                                    ('4.0.0-rc.5',['net/kaleidoscope/cookery/recipe/FoodRecipeRegistry.class']),
                                    ('4.0.0-rc.5',['dev/stargenesis/integrations/RecipeCatalog.class']),
                                    ('4.0.0-rc.5',[])]:
                with zipfile.ZipFile(jar,'w') as archive:
                    archive.writestr('plugin.yml',"version: '"+version+"'\n")
                    for name in classes:archive.writestr(name,b'class-byte-fixture')
                with self.assertRaises(RuntimeError):builder.verified_plugin(jar,'4.0.0-rc.5','stargenesis-cookery')
            with zipfile.ZipFile(jar,'w') as archive:
                archive.writestr('plugin.yml',"version: '4.0.0-rc.5'\n")
                archive.writestr('dev/stargenesis/cookery/Bridge.class',b'class-byte-fixture')
            builder.verified_plugin(jar,'4.0.0-rc.5','stargenesis-cookery')

    def test_preparation_with_wrong_lock_cannot_authorize_optional_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/'build';(root/'scripts').mkdir(parents=True)
            (root/'scripts/craftengine-api-lock.json').write_text('{}')
            def run(command,**kwargs):
                folder=Path(command[command.index('--work-dir')+1])
                (folder/'preparation.json').write_text(json.dumps({'sourceLockSha256':'0'*64}))
            with patch.object(builder.subprocess,'run',side_effect=run):
                with self.assertRaisesRegex(RuntimeError,'does not match source lock'):
                    builder.prepare_dependencies(root,root/'jdk/bin/java',('craftengine-bridge',))

    def test_selected_optional_modules_and_their_locked_build_inputs_are_isolated(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)/'source';root.mkdir()
            (root/'pom.xml').write_text('<project/>');(root/'.mvn').mkdir()
            for module in (*builder.MODULES,'stargenesis-cookery','stargenesis-craftengine','stargenesis-helstera'):
                folder=root/module;folder.mkdir();(folder/'pom.xml').write_text('<project/>')
                (folder/'target').mkdir();(folder/'target/prior.jar').write_bytes(b'old')
            scripts=root/'scripts';scripts.mkdir()
            for name in ('build_release_candidate.py','prepare_craftengine_api.py','craftengine-api-lock.json',
                         'prepare_cookery_api.py','cookery-api-lock.json','cookery-api.init.gradle'):
                (scripts/name).write_text('locked fixture '+name)
            destination=Path(temporary)/'copy'
            builder.copy_build_inputs(root,destination,('cookery-bridge',))
            self.assertTrue((destination/'stargenesis-cookery/pom.xml').is_file())
            self.assertFalse((destination/'stargenesis-craftengine').exists())
            self.assertFalse((destination/'stargenesis-helstera').exists())
            self.assertFalse(list(destination.rglob('prior.jar')))
            self.assertEqual((scripts/'cookery-api-lock.json').read_bytes(),(destination/'scripts/cookery-api-lock.json').read_bytes())
            self.assertTrue((destination/'scripts/craftengine-api-lock.json').is_file())
            with self.assertRaises(ValueError):builder.selected_modules(('untrusted-profile',))

    def test_optional_module_with_missing_reports_cannot_hide_behind_base_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);reports=root/'stargenesis-paper/target/surefire-reports';reports.mkdir(parents=True)
            (reports/'TEST-base.xml').write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
            with self.assertRaises(RuntimeError):builder.test_totals(root,('stargenesis-paper','stargenesis-cookery'))

    def test_posix_build_uses_shell_for_nonexecutable_wrapper_and_records_profile(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);java=root/'jdk/bin/java';java.parent.mkdir(parents=True)
            with patch.object(builder.os,'name','posix'),patch.object(builder.subprocess,'run') as run:
                run.return_value.returncode=0
                command=builder.run_build(root,java,root/'build.log',0,('cookery-bridge',))
                self.assertEqual(['sh',str(root/'mvnw')],command[:2])
                self.assertIn('-Pcookery-bridge',command)
                self.assertEqual(str(java.parent.parent),run.call_args.kwargs['env']['JAVA_HOME'])

    def test_failed_empty_or_skipped_reports_cannot_be_packaged(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reports = root / 'stargenesis-paper/target/surefire-reports'
            reports.mkdir(parents=True)
            with self.assertRaises(RuntimeError):
                builder.test_totals(root)
            for counts in ((0, 0, 0, 0), (1, 1, 0, 0), (1, 0, 1, 0), (1, 0, 0, 1)):
                tests, failures, errors, skipped = counts
                (reports / 'TEST-fixture.xml').write_text(
                    f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" skipped="{skipped}"/>')
                with self.assertRaises(RuntimeError):
                    builder.test_totals(root)
            (reports / 'TEST-fixture.xml').write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
            self.assertEqual({'reports': 1, 'tests': 3, 'failures': 0, 'errors': 0, 'skipped': 0}, builder.test_totals(root))

    def test_isolated_inputs_exclude_prior_artifacts_and_version_edits_leave_source_untouched(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'source'
            root.mkdir()
            pom = '<project><version>4.0.0-SNAPSHOT</version></project>'
            (root / 'pom.xml').write_text(pom)
            (root / '.mvn').mkdir()
            for module in builder.MODULES:
                directory = root / module
                directory.mkdir()
                (directory / 'pom.xml').write_text('<project><parent><version>4.0.0-SNAPSHOT</version></parent></project>')
                (directory / 'target').mkdir()
                (directory / 'target/prior.jar').write_bytes(b'old artifact')
                (directory / '__pycache__').mkdir()
                (directory / '__pycache__/prior.pyc').write_bytes(b'old bytecode')
            destination = Path(temporary) / 'isolated'
            builder.copy_build_inputs(root, destination)
            self.assertFalse(list(destination.rglob('prior.*')))
            builder.set_reactor_version(destination / 'pom.xml', '4.0.0-rc.3', True, '4.0.0-SNAPSHOT')
            for module in builder.MODULES:
                builder.set_reactor_version(destination / module / 'pom.xml', '4.0.0-rc.3', False, '4.0.0-SNAPSHOT')
            self.assertEqual(pom, (root / 'pom.xml').read_text())
            self.assertEqual('4.0.0-rc.3', builder.project_version(destination / 'pom.xml'))

    def test_existing_evidence_is_preserved_before_any_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / 'existing.json'
            before = json.dumps({'fixture': 'original accepted evidence'}).encode()
            evidence.write_bytes(before)
            output = root / 'release'
            result = subprocess.run([sys.executable, str(Path(builder.__file__).resolve()), '--version', '4.0.0-rc.3',
                                     '--java', str(Path(sys.executable).resolve()), '--output-directory', str(output),
                                     '--evidence-file', str(evidence)], capture_output=True, text=True, timeout=15)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Release evidence already exists', result.stderr)
            self.assertEqual(before, evidence.read_bytes())
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
