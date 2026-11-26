from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from verify_ecology_robustness_study import (CONFIG, DEFAULTS, EXCLUDED, INITIAL_POPULATION,
    expected_profiles, expected_seeds, original_seeds, read_json, summarize, validate_report)


def fixture(baseline=2, paired=2):
    plan = {"engineVersion": "genesis-ecology/2", "config": deepcopy(CONFIG), "sampleSeed": 2026112602,
            "sampling": "Fixed held-out seeds", "seeds": expected_seeds(baseline), "baselineSeeds": baseline,
            "pairedSeeds": paired, "profiles": expected_profiles(), "excludedProfiles": EXCLUDED[:],
            "expectedCases": baseline + 17 * paired, "scope": "Finite paired study"}
    cases = []
    for profile, programs in plan["profiles"].items():
        for index, seed in enumerate(plan["seeds"][:baseline if profile == "default" else paired]):
            milestones = [{"tick": tick, "populationBySpecies": deepcopy(INITIAL_POPULATION),
                           "soilMatter": 900, "detritusMatter": 86, "births": 0, "deaths": 0,
                           "maxGeneration": 0, "evolvedOrganisms": 0} for tick in range(256, 1537, 256)]
            final = dict(milestones[-1], worldId=f"ecology-v2-robustness-{index}", seed=seed,
                         totalMatter=1000, organismMatter=14, population=7,
                         matterBySpecies={species: count * 2 for species, count in INITIAL_POPULATION.items()},
                         processedMatter=0, programs=deepcopy(programs), stateHash="a" * 64,
                         terminal=True, reason="DURATION_BUDGET")
            cases.append({"profile": profile, "worldId": final["worldId"], "seed": seed,
                          "firstSpeciesExtinction": {}, "minimumPopulationBySpecies": deepcopy(INITIAL_POPULATION),
                          "maxPopulation": 7, "maxGenerationSeen": 0, "generationBudgetReached": False,
                          "birthBudgetReached": False, "maxEventsPerStep": 1, "maxEvolvedOrganisms": 0,
                          "milestones": milestones, "finalState": final, "replayVerified": True})
    return {"plan": deepcopy(plan), "complete": True, "cases": cases}, plan


def extinct(case, tick=100):
    case["firstSpeciesExtinction"] = {species: tick for species in DEFAULTS}
    case["minimumPopulationBySpecies"] = {species: 0 for species in DEFAULTS}
    final = case["finalState"]
    final.update(tick=tick, population=0, populationBySpecies={}, matterBySpecies={},
                 organismMatter=0, detritusMatter=100, deaths=7,
                 reason="DURATION_BUDGET" if tick == 1536 else "EXTINCTION")
    case["milestones"] = [row for row in case["milestones"] if row["tick"] < tick] + [{key: deepcopy(final[key]) for key in
        ("tick", "populationBySpecies", "soilMatter", "detritusMatter", "births", "deaths", "maxGeneration", "evolvedOrganisms")}]


class EcologyRobustnessVerifierTest(unittest.TestCase):
    def setUp(self):
        self.primary, self.plan = fixture()
        self.comparison, self.comparison_plan = fixture(1, 1)
        self.prior = {"engineVersion": "genesis-ecology/2", "complete": True,
                      "cases": [{"seed": seed} for seed in original_seeds()]}

    def result(self):
        return summarize(self.primary, self.plan, self.comparison, self.comparison_plan, self.prior)

    def rejects(self):
        with self.assertRaises((ValueError, KeyError, TypeError)):
            self.result()

    def test_seed_generator_matches_java_signed_long_samples(self):
        self.assertEqual([1648842534038410324, -5068950895295806940, 1713069980100688413,
                          5354633896267523092, -7708135224747630074, 7578474564282655210], expected_seeds(6))
        self.assertEqual(32, len(original_seeds()))
        self.assertFalse(set(expected_seeds(128)) & original_seeds())

    def test_complete_paired_study_reports_distinct_seeds_not_cases(self):
        result = self.result()
        self.assertEqual(36, result["primaryCases"])
        self.assertEqual(2, result["heldOutIndependentSeeds"])
        self.assertEqual(18, result["crossJdkRepeatedCases"])
        self.assertFalse(result["crossJdkAddsIndependentSeeds"])
        self.assertEqual(2, result["profiles"]["decomposer-intake-minus"]["pairedBaselineCases"])

    def test_extinction_is_accepted_retained_and_compared_with_baseline(self):
        extinct(self.primary["cases"][2])
        extinct(self.comparison["cases"][1])
        result = self.result()["profiles"]["decomposer-intake-minus"]
        self.assertEqual(1, result["threeSpeciesAt1536"])
        self.assertEqual(1, len(result["extinctionCases"]))
        self.assertEqual(1, result["pairedSurvivalOutcomes"]["baselineOnlySurvives"])

    def test_last_tick_extinction_uses_actual_duration_first_priority(self):
        extinct(self.primary["cases"][2], 1536)
        extinct(self.comparison["cases"][1], 1536)
        result = self.result()["profiles"]["decomposer-intake-minus"]
        self.assertEqual(1, result["threeSpeciesAt1536"])
        self.assertEqual({species: 1536 for species in DEFAULTS}, result["extinctionCases"][0]["firstSpeciesExtinction"])
        self.primary["cases"][2]["finalState"]["reason"] = "EXTINCTION"
        self.rejects()

    def test_actual_128_event_budget_boundary(self):
        self.primary["cases"][0]["maxEventsPerStep"] = 128
        self.comparison["cases"][0]["maxEventsPerStep"] = 128
        self.assertTrue(self.result()["passed"])
        self.primary["cases"][0]["maxEventsPerStep"] = 129
        self.rejects()

    def test_incomplete_or_missing_case_rejected(self):
        self.primary["complete"] = False
        self.rejects()
        self.primary["complete"] = True
        self.primary["cases"].pop()
        self.rejects()

    def test_duplicate_case_cannot_replace_missing_case(self):
        self.primary["cases"][-1] = deepcopy(self.primary["cases"][0])
        self.rejects()

    def test_report_plan_must_match_sidecar(self):
        self.primary["plan"]["scope"] += " changed"
        self.rejects()

    def test_config_change_rejected_even_when_sidecar_matches(self):
        self.plan["config"]["initialMatter"] += 1
        self.primary["plan"] = deepcopy(self.plan)
        self.rejects()

    def test_second_parameter_change_rejected(self):
        self.plan["profiles"]["producer-intake-minus"]["producer"]["mutationPermille"] = 500
        self.primary["plan"] = deepcopy(self.plan)
        self.rejects()

    def test_seed_drift_and_prior_overlap_rejected(self):
        self.plan["seeds"][0] = 42
        self.primary["plan"] = deepcopy(self.plan)
        self.rejects()

    def test_prior_seed_evidence_cannot_be_empty(self):
        self.prior["cases"] = []
        self.rejects()

    def test_unverified_replay_and_nonterminal_state_rejected(self):
        self.primary["cases"][0]["replayVerified"] = False
        self.rejects()
        self.primary["cases"][0]["replayVerified"] = True
        self.primary["cases"][0]["finalState"]["terminal"] = False
        self.rejects()

    def test_matter_population_and_budget_inconsistencies_rejected(self):
        for change in (("totalMatter", 1001), ("population", 8), ("births", 513), ("tick", 1535)):
            with self.subTest(change=change):
                primary = deepcopy(self.primary)
                primary["cases"][0]["finalState"][change[0]] = change[1]
                with self.assertRaises(ValueError):
                    validate_report(primary, self.plan, original_seeds())

    def test_extinction_and_milestone_consistency_rejected(self):
        self.primary["cases"][0]["firstSpeciesExtinction"] = {"producer": 100}
        self.rejects()
        self.primary["cases"][0]["firstSpeciesExtinction"] = {}
        self.primary["cases"][0]["milestones"].pop(0)
        self.rejects()

    def test_cross_jdk_result_change_rejected(self):
        self.comparison["cases"][0]["finalState"]["stateHash"] = "b" * 64
        self.rejects()

    def test_bool_cannot_masquerade_as_numeric_evidence(self):
        self.primary["cases"][0]["maxEventsPerStep"] = True
        self.rejects()

    def test_duplicate_json_keys_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"complete": false, "complete": true}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Duplicate JSON key"):
                read_json(path)


if __name__ == "__main__":
    unittest.main()
