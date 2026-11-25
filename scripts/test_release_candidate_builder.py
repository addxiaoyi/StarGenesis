"""Regression tests for isolated release input and test/evidence rejection boundaries."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import build_release_candidate as builder


class ReleaseCandidateBuilderTest(unittest.TestCase):
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
