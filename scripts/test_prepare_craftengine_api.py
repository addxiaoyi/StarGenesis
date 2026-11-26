import hashlib
import unittest
import xml.etree.ElementTree as ET
from prepare_craftengine_api import artifact_pom, verified_bytes


class CraftEngineArtifactTest(unittest.TestCase):
    def test_corrupt_and_wrong_size_artifacts_are_rejected(self):
        data = b'published API artifact'
        expected = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
        self.assertEqual(data, verified_bytes(data, expected))
        for changed in (data + b'x', data[:-1] + b'x'):
            with self.assertRaises(ValueError): verified_bytes(changed, expected)

    def test_locked_coordinate_preserves_provider_license(self):
        pom = b'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>net.momirealms</groupId><artifactId>craft-engine-core</artifactId><version>26.9.1</version><licenses><license><name>GNU GPL v3</name></license></licenses></project>'
        changed = artifact_pom(pom, 'core', '26.9.1-verified')
        parsed = ET.fromstring(changed)
        ns = '{http://maven.apache.org/POM/4.0.0}'
        self.assertEqual('26.9.1-verified', parsed.findtext(ns + 'version'))
        self.assertEqual('GNU GPL v3', parsed.findtext(ns+'licenses/'+ns+'license/'+ns+'name'))

    def test_wrong_module_pom_cannot_be_installed_as_another_api(self):
        pom = b'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>net.momirealms</groupId><artifactId>craft-engine-core</artifactId><version>26.9.1</version></project>'
        with self.assertRaises(ValueError): artifact_pom(pom, 'bukkit', '26.9.1-verified')


if __name__ == '__main__': unittest.main()
