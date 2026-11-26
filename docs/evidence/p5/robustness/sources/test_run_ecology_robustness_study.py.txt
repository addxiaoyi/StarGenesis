import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import run_ecology_robustness_study as runner


class PinnedEcologyRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        (self.root / "scripts").mkdir()
        self.worker = self.root / "scripts/EcologyRobustnessStudy.java"
        self.worker.write_text('// genesis-ecology/2 study fixture\n', encoding="utf-8")
        self.jar = self.root / "plugin.jar"
        self.jar.write_bytes(b"same advertised engine v2, original implementation")
        self.java = self.root / "java.exe"
        self.java.write_bytes(b"not executed; subprocess is mocked")
        self.output = self.root / "evidence/study.json"
        self.plan = self.output.with_name(self.output.name + ".plan.json")
        self.runtime = self.output.with_name(self.output.name + ".runtime.json")
        self.lock = self.output.with_name(self.output.name + ".run.lock")
        self.expected = hashlib.sha256(self.jar.read_bytes()).hexdigest()

    def invoke(self, **overrides):
        arguments = {"java": self.java, "plugin_jar": self.jar, "expected_jar_sha256": self.expected,
                     "output": self.output, "baseline_seeds": 2, "paired_seeds": 1,
                     "repository_root": self.root}
        arguments.update(overrides)
        return runner.run_study(**arguments)

    def successful_worker(self, command, **kwargs):
        self.assertTrue(self.runtime.is_file(), "Immutable binding must exist before the worker starts")
        self.output.write_text('{"complete":true}', encoding="utf-8")
        self.plan.write_text('{"fixture":"worker plan"}', encoding="utf-8")
        return subprocess.CompletedProcess(command, 0)

    def first_run(self):
        with patch.object(runner.subprocess, "run", side_effect=self.successful_worker):
            return self.invoke()

    def test_first_run_persists_exact_binding_before_streamed_list_subprocess(self):
        with patch.object(runner.subprocess, "run", side_effect=self.successful_worker) as process:
            result = self.invoke()
        self.assertEqual(result, json.loads(self.runtime.read_text(encoding="utf-8")))
        self.assertEqual(self.expected, result["pluginJarSha256"])
        self.assertEqual(runner.sha256(self.worker), result["workerSourceSha256"])
        self.assertEqual(str(self.java), result["java"])
        self.assertEqual(runner.sha256(self.java), result["javaSha256"])
        self.assertEqual(2, result["baselineSeeds"])
        command = process.call_args.args[0]
        self.assertIsInstance(command, list)
        self.assertEqual([str(self.java), *runner.JVM_OPTIONS, "-cp", str(self.jar), str(self.worker),
                          str(self.output), "2", "1"], command)
        self.assertEqual({"cwd": str(self.root), "shell": False, "check": True}, process.call_args.kwargs)
        self.assertFalse(self.lock.exists())

    def test_resume_requires_same_binding_and_leaves_original_manifest_bytes(self):
        self.first_run()
        original = self.runtime.read_bytes()
        with patch.object(runner.subprocess, "run", side_effect=self.successful_worker) as process:
            self.invoke(resume=True)
        self.assertEqual("--resume", process.call_args.args[0][-1])
        self.assertEqual(original, self.runtime.read_bytes())

    def test_jar_drift_is_rejected_even_with_new_expected_hash_and_same_engine(self):
        self.first_run()
        original = self.runtime.read_bytes()
        self.jar.write_bytes(b"same advertised engine v2, different implementation")
        with patch.object(runner.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "required expected hash"):
                self.invoke(resume=True)
            with self.assertRaisesRegex(ValueError, "Resume runtime binding"):
                self.invoke(resume=True, expected_jar_sha256=runner.sha256(self.jar))
        process.assert_not_called()
        self.assertEqual(original, self.runtime.read_bytes())

    def test_worker_source_drift_rejected_before_any_jvm(self):
        self.first_run()
        self.worker.write_text('// genesis-ecology/2 different study implementation\n', encoding="utf-8")
        with patch.object(runner.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "Resume runtime binding"):
                self.invoke(resume=True)
        process.assert_not_called()

    def test_java_executable_drift_rejected_before_any_jvm(self):
        self.first_run()
        self.java.write_bytes(b"different executable at the same path")
        with patch.object(runner.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "Resume runtime binding"):
                self.invoke(resume=True)
        process.assert_not_called()

    def test_missing_manifest_report_or_plan_rejects_resume(self):
        for missing in (self.runtime, self.output, self.plan):
            with self.subTest(missing=missing.name):
                for path in (self.runtime, self.output, self.plan):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("{}", encoding="utf-8")
                missing.unlink()
                with patch.object(runner.subprocess, "run") as process:
                    with self.assertRaisesRegex(ValueError, "Resume requires"):
                        self.invoke(resume=True)
                process.assert_not_called()

    def test_existing_evidence_or_binding_is_never_overwritten(self):
        for existing in (self.output, self.plan, self.runtime):
            with self.subTest(existing=existing.name):
                for path in (self.output, self.plan, self.runtime):
                    path.unlink(missing_ok=True)
                existing.parent.mkdir(parents=True, exist_ok=True)
                existing.write_bytes(b"preserved evidence")
                with patch.object(runner.subprocess, "run") as process:
                    with self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
                        self.invoke()
                process.assert_not_called()
                self.assertEqual(b"preserved evidence", existing.read_bytes())

    def test_seed_budget_or_java_path_change_rejects_resume(self):
        self.first_run()
        other_java = self.root / "other-java.exe"
        other_java.write_bytes(b"not executed")
        for overrides in ({"baseline_seeds": 3}, {"paired_seeds": 2}, {"java": other_java}):
            with self.subTest(overrides=overrides), patch.object(runner.subprocess, "run") as process:
                with self.assertRaisesRegex(ValueError, "Resume runtime binding"):
                    self.invoke(resume=True, **overrides)
                process.assert_not_called()

    def test_worker_failure_preserves_binding_and_progress_for_explicit_resume(self):
        def failing_worker(command, **kwargs):
            self.successful_worker(command, **kwargs)
            raise subprocess.CalledProcessError(7, command)
        with patch.object(runner.subprocess, "run", side_effect=failing_worker):
            with self.assertRaises(subprocess.CalledProcessError) as error:
                self.invoke()
        self.assertEqual(7, error.exception.returncode)
        self.assertFalse(self.lock.exists(), "Normal worker failure releases only its own lock")
        original = self.runtime.read_bytes()
        with patch.object(runner.subprocess, "run", side_effect=self.successful_worker):
            self.invoke(resume=True)
        self.assertEqual(original, self.runtime.read_bytes())

    def test_input_changes_during_worker_are_detected_on_exit(self):
        for changed in (self.jar, self.worker, self.java):
            with self.subTest(changed=changed.name):
                for path in (self.output, self.plan, self.runtime):
                    path.unlink(missing_ok=True)
                self.jar.write_bytes(b"same advertised engine v2, original implementation")
                self.worker.write_text('// genesis-ecology/2 study fixture\n', encoding="utf-8")
                self.java.write_bytes(b"not executed; subprocess is mocked")
                def changing_worker(command, **kwargs):
                    self.successful_worker(command, **kwargs)
                    changed.write_bytes(b"changed while the worker ran")
                    return subprocess.CompletedProcess(command, 0)
                with patch.object(runner.subprocess, "run", side_effect=changing_worker):
                    with self.assertRaisesRegex(RuntimeError, "bytes changed"):
                        self.invoke()
                binding = json.loads(self.runtime.read_text(encoding="utf-8"))
                hash_key = "pluginJarSha256" if changed == self.jar else "workerSourceSha256" if changed == self.worker else "javaSha256"
                self.assertNotEqual(runner.sha256(changed), binding[hash_key])

    def test_concurrent_resume_is_rejected_before_any_second_worker(self):
        self.first_run()
        attempts = []
        def worker(command, **kwargs):
            self.assertTrue(self.lock.is_file())
            with self.assertRaisesRegex(ValueError, "run lock exists"):
                self.invoke(resume=True)
            attempts.append(command)
            return self.successful_worker(command, **kwargs)
        with patch.object(runner.subprocess, "run", side_effect=worker) as process:
            self.invoke(resume=True)
        self.assertEqual(1, len(attempts))
        self.assertEqual(1, process.call_count)
        self.assertFalse(self.lock.exists())

    def test_stale_lock_is_preserved_until_explicit_owner_inspection(self):
        self.first_run()
        self.lock.write_bytes(b"original owner evidence\n")
        with patch.object(runner.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "run lock exists"):
                self.invoke(resume=True)
        process.assert_not_called()
        self.assertEqual(b"original owner evidence\n", self.lock.read_bytes())

    def test_replaced_lock_is_not_deleted_as_if_it_were_ours(self):
        def replacing_worker(command, **kwargs):
            self.successful_worker(command, **kwargs)
            self.lock.write_bytes(b"another owner\n")
            return subprocess.CompletedProcess(command, 0)
        with patch.object(runner.subprocess, "run", side_effect=replacing_worker):
            with self.assertRaisesRegex(RuntimeError, "run lock changed"):
                self.invoke()
        self.assertEqual(b"another owner\n", self.lock.read_bytes())

    def test_invalid_expected_hash_and_seed_budgets_do_not_create_binding(self):
        for overrides in ({"expected_jar_sha256": "short"}, {"expected_jar_sha256": "g" * 64},
                          {"baseline_seeds": 0}, {"baseline_seeds": 129}, {"paired_seeds": 3},
                          {"paired_seeds": True}):
            with self.subTest(overrides=overrides), patch.object(runner.subprocess, "run") as process:
                with self.assertRaises(ValueError):
                    self.invoke(**overrides)
                process.assert_not_called()
                self.assertFalse(self.runtime.exists())

    def test_success_without_committed_worker_files_is_rejected(self):
        with patch.object(runner.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(RuntimeError, "without a committed report"):
                self.invoke()
        self.assertTrue(self.runtime.exists())

    def test_manifest_mutation_during_worker_is_rejected(self):
        def changing_worker(command, **kwargs):
            self.successful_worker(command, **kwargs)
            binding = json.loads(self.runtime.read_text(encoding="utf-8"))
            binding["workerSourceSha256"] = "0" * 64
            self.runtime.write_text(json.dumps(binding), encoding="utf-8")
            return subprocess.CompletedProcess(command, 0)
        with patch.object(runner.subprocess, "run", side_effect=changing_worker):
            with self.assertRaisesRegex(RuntimeError, "binding changed"):
                self.invoke()

    def test_boolean_count_in_manifest_cannot_match_integer_count(self):
        self.first_run()
        binding = json.loads(self.runtime.read_text(encoding="utf-8"))
        binding["pairedSeeds"] = True
        self.runtime.write_text(json.dumps(binding), encoding="utf-8")
        with patch.object(runner.subprocess, "run") as process:
            with self.assertRaisesRegex(ValueError, "invalid numeric fields"):
                self.invoke(resume=True)
        process.assert_not_called()


if __name__ == "__main__":
    unittest.main()
