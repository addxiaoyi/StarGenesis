"""Reject incomplete or rewritten shutdown evidence without launching a server."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import server_lifecycle_smoke as lifecycle


class LifecycleEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.data = Path(self.temporary.name)
        self.file = self.data / "labs/lab/player.lab.json"
        self.file.parent.mkdir(parents=True)
        self.workflow = {"identities": {"alice": "player"}, "labAtStop": {"tick": 2}}
        self.saved = {"owner": "player", "snapshot": {"worldId": "lab-a", "tick": 3,
                      "history": [{"stateHash": "a"}, {"stateHash": "b"}, {"stateHash": "c"}], "stateHash": "c"}}

    def verify(self, previous=None):
        self.file.write_text(json.dumps(self.saved), encoding="utf-8")
        return lifecycle.verify_lab_shutdown(self.data, self.workflow, previous)

    def test_final_checkpoint_must_cover_observed_progress_with_complete_matching_history(self):
        self.verify()
        original = copy.deepcopy(self.saved)
        for mutation in (lambda doc: doc.update(owner="someone-else"),
                         lambda doc: doc["snapshot"].update(tick=1),
                         lambda doc: doc["snapshot"].update(stateHash="wrong"),
                         lambda doc: doc["snapshot"]["history"].pop()):
            with self.subTest(mutation=mutation):
                self.saved = copy.deepcopy(original)
                mutation(self.saved)
                with self.assertRaises(AssertionError):
                    self.verify()

    def test_resume_must_extend_same_world_without_rewriting_prior_history(self):
        previous = {"worldId": "lab-a", "tick": 2, "history": [{"stateHash": "a"}, {"stateHash": "b"}]}
        self.verify(previous)
        for changed in ({**previous, "worldId": "lab-b"},
                        {**previous, "tick": 3},
                        {**previous, "history": [{"stateHash": "tampered"}, {"stateHash": "b"}]}):
            with self.subTest(previous=changed), self.assertRaises(AssertionError):
                self.verify(changed)

    def test_manifest_detects_changed_added_and_deleted_evidence(self):
        self.verify()
        before = lifecycle.storage_manifest(self.data)
        self.file.write_text("changed")
        self.assertNotEqual(before, lifecycle.storage_manifest(self.data))
        self.verify()
        extra = self.file.with_name("extra.lab.json")
        extra.write_text("{}")
        self.assertNotEqual(before, lifecycle.storage_manifest(self.data))
        extra.unlink()
        self.assertEqual(before, lifecycle.storage_manifest(self.data))
        self.file.unlink()
        self.assertNotEqual(before, lifecycle.storage_manifest(self.data))

    def test_lease_check_never_creates_missing_lock_files(self):
        with self.assertRaises(FileNotFoundError):
            lifecycle.assert_worker_locks_released(self.data)
        self.assertFalse(any(self.data.rglob(".worker.lock")))


if __name__ == "__main__":
    unittest.main()
