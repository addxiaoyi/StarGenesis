#!/usr/bin/env python3
"""Independently validate the fixed P5 v2 OAT study; extinction is data, not failure."""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re


CONFIG = {"initialMatter": 1000, "initialDetritus": 86, "maxOrganisms": 32,
          "maxGenerations": 8, "maxTicks": 1536, "maxBirths": 512,
          "maxLedgerAttempts": 98304}
INITIAL_POPULATION = {"decomposer": 2, "grazer": 2, "producer": 3}
DEFAULTS = {
    "decomposer": {"species": "decomposer", "role": "DECOMPOSER", "intake": 3,
                   "reproductionMass": 4, "lifespanSteps": 320, "mutationPermille": 80},
    "grazer": {"species": "grazer", "role": "GRAZER", "intake": 4,
               "reproductionMass": 24, "lifespanSteps": 512, "mutationPermille": 100},
    "producer": {"species": "producer", "role": "PRODUCER", "intake": 6,
                 "reproductionMass": 4, "lifespanSteps": 320, "mutationPermille": 150},
}
EXCLUDED = ["grazer-lifespanSteps-plus: outside existing public parameter bounds; not clamped or substituted"]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def integer(value, low: int, high: int, label: str) -> int:
    require(type(value) is int and low <= value <= high, f"Invalid {label}: {value!r}")
    return value


class JavaRandom:
    """java.util.Random's 48-bit generator and signed nextLong arithmetic."""
    def __init__(self, seed: int):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def next32(self) -> int:
        self.state = (self.state * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        value = self.state >> 16
        return value if value < (1 << 31) else value - (1 << 32)

    def next_long(self) -> int:
        value = ((self.next32() << 32) + self.next32()) & ((1 << 64) - 1)
        return value if value < (1 << 63) else value - (1 << 64)


def original_seeds() -> set[int]:
    random = JavaRandom(20261126)
    return {0, 42, -(1 << 63), (1 << 63) - 1, *(random.next_long() for _ in range(28))}


def expected_seeds(count: int) -> list[int]:
    used = original_seeds()
    random = JavaRandom(2026112602)
    selected = []
    while len(selected) < count:
        seed = random.next_long()
        if seed not in used:
            used.add(seed)
            selected.append(seed)
    return selected


def expected_profiles() -> dict:
    profiles = {"default": deepcopy(DEFAULTS)}
    for species, program in DEFAULTS.items():
        for parameter in ("intake", "reproductionMass", "lifespanSteps"):
            for direction, sign in (("minus", -1), ("plus", 1)):
                delta = max(1, program[parameter] // 4) if parameter == "lifespanSteps" else 1
                value = program[parameter] + sign * delta
                low, high = {"intake": (1, 8), "reproductionMass": (2, 32), "lifespanSteps": (1, 512)}[parameter]
                if not low <= value <= high:
                    continue
                variant = deepcopy(DEFAULTS)
                variant[species][parameter] = value
                profiles[f"{species}-{parameter}-{direction}"] = variant
    return profiles


def prior_seed_set(prior: dict) -> set[int]:
    require(prior.get("complete") is True and prior.get("engineVersion") == "genesis-ecology/2",
            "Prior validation must be complete ecology v2 evidence")
    require(type(prior.get("cases")) is list, "Missing prior validation cases")
    seeds = {integer(case.get("seed"), -(1 << 63), (1 << 63) - 1, "prior seed") for case in prior["cases"]}
    require(seeds == original_seeds(), "Prior validation seed set differs from the declared 32 original seeds")
    return seeds


def validate_plan(plan: dict, prior: set[int]) -> None:
    require(type(plan) is dict and plan.get("engineVersion") == "genesis-ecology/2", "Wrong study engine")
    require(plan.get("config") == CONFIG, "Study configuration drift")
    for name, value in CONFIG.items():
        integer(plan["config"].get(name), value, value, f"config.{name}")
    integer(plan.get("sampleSeed"), 2026112602, 2026112602, "sampleSeed")
    baseline = integer(plan.get("baselineSeeds"), 1, 128, "baselineSeeds")
    paired = integer(plan.get("pairedSeeds"), 1, baseline, "pairedSeeds")
    require(plan.get("seeds") == expected_seeds(baseline), "Held-out seed sequence drift")
    for seed in plan["seeds"]:
        integer(seed, -(1 << 63), (1 << 63) - 1, "seed")
    require(not (set(plan["seeds"]) & prior), "Held-out seeds overlap prior validation")
    require(plan.get("profiles") == expected_profiles(), "OAT profiles or defaults drift")
    for programs in plan["profiles"].values():
        for program in programs.values():
            for field in ("intake", "reproductionMass", "lifespanSteps", "mutationPermille"):
                integer(program[field], 0, 512, field)
    require(plan.get("excludedProfiles") == EXCLUDED, "Excluded bound case changed")
    integer(plan.get("expectedCases"), baseline + 17 * paired, baseline + 17 * paired, "expectedCases")
    require(type(plan.get("scope")) is str and bool(plan["scope"].strip()), "Missing study scope")
    require(type(plan.get("sampling")) is str and bool(plan["sampling"].strip()), "Missing sampling declaration")


def species_values(values, high: int, label: str, all_species: bool = False) -> dict:
    require(type(values) is dict and set(values) <= set(DEFAULTS), f"Invalid {label} species")
    if all_species:
        require(set(values) == set(DEFAULTS), f"Missing {label} species")
    for species, value in values.items():
        integer(value, 0, high, f"{label}.{species}")
    return values


def validate_case(case: dict, profile: str, index: int, plan: dict) -> None:
    context = f"{profile}/{index}"
    require(case.get("profile") == profile, f"Wrong profile {context}")
    require(case.get("seed") == plan["seeds"][index], f"Wrong seed {context}")
    integer(case.get("seed"), -(1 << 63), (1 << 63) - 1, f"seed {context}")
    require(case.get("worldId") == f"ecology-v2-robustness-{index}", f"Wrong world ID {context}")
    require(case.get("replayVerified") is True, f"Unverified replay {context}")
    final = case.get("finalState")
    require(type(final) is dict, f"Missing final state {context}")
    require(final.get("worldId") == case["worldId"] and final.get("seed") == case["seed"], f"Final identity mismatch {context}")
    integer(final.get("seed"), -(1 << 63), (1 << 63) - 1, "final seed")
    require(final.get("terminal") is True, f"Nonterminal state {context}")
    tick = integer(final.get("tick"), 1, CONFIG["maxTicks"], "final tick")
    population = integer(final.get("population"), 0, 32, "final population")
    # EcologyWorld.step checks duration before extinction. Zero population at the
    # horizon is valid, but its reason is still DURATION_BUDGET.
    require(final.get("reason") == ("DURATION_BUDGET" if tick == 1536 else "EXTINCTION"), f"Wrong terminal reason {context}")
    require(tick == 1536 or population == 0, f"Early non-extinction {context}")
    births = integer(final.get("births"), 0, 512, "final births")
    deaths = integer(final.get("deaths"), 0, 519, "final deaths")
    require(population == 7 + births - deaths, f"Population accounting mismatch {context}")
    require(final.get("programs") == plan["profiles"][profile], f"Final programs changed {context}")
    require(type(final.get("stateHash")) is str and re.fullmatch(r"[0-9a-f]{64}", final["stateHash"]), f"Invalid final commitment {context}")
    integer(final.get("totalMatter"), 1000, 1000, "total matter")
    matter = [integer(final.get(field), 0, 1000, field) for field in ("soilMatter", "detritusMatter", "organismMatter")]
    require(sum(matter) == 1000, f"Matter conservation mismatch {context}")
    populations = species_values(final.get("populationBySpecies"), 32, "final population")
    matters = species_values(final.get("matterBySpecies"), 1000, "final matter")
    require(sum(populations.values()) == population and sum(matters.values()) == matter[2], f"Species totals mismatch {context}")
    for species in DEFAULTS:
        require((populations.get(species, 0) > 0) == (matters.get(species, 0) > 0), f"Species matter mismatch {context}/{species}")
    generation = integer(final.get("maxGeneration"), 0, 8, "final generation")
    evolved = integer(final.get("evolvedOrganisms"), 0, population, "final evolved")
    integer(final.get("processedMatter"), 0, 1000 * CONFIG["maxLedgerAttempts"], "processed matter")
    maximum_population = integer(case.get("maxPopulation"), max(7, population), 32, "maximum population")
    maximum_generation = integer(case.get("maxGenerationSeen"), generation, 8, "maximum generation")
    integer(case.get("maxEventsPerStep"), 0, 128, "maximum events")
    integer(case.get("maxEvolvedOrganisms"), evolved, maximum_population, "maximum evolved")
    require(type(case.get("generationBudgetReached")) is bool and case["generationBudgetReached"] == (maximum_generation == 8), "Generation flag mismatch")
    require(type(case.get("birthBudgetReached")) is bool and case["birthBudgetReached"] == (births == 512), "Birth flag mismatch")
    minima = species_values(case.get("minimumPopulationBySpecies"), 32, "minimum population", True)
    extinct = case.get("firstSpeciesExtinction")
    require(type(extinct) is dict and set(extinct) <= set(DEFAULTS), "Invalid extinction species")
    require(set(extinct) == {species for species, value in minima.items() if value == 0}, "Extinction/minimum mismatch")
    for species in DEFAULTS:
        require(minima[species] <= min(INITIAL_POPULATION[species], populations.get(species, 0)), "Impossible population minimum")
        require((species in extinct) == (populations.get(species, 0) == 0), "Extinct species returned without a source")
    for value in extinct.values():
        integer(value, 1, tick, "first extinction tick")
    milestones = case.get("milestones")
    require(type(milestones) is list, "Missing milestones")
    expected_ticks = list(range(256, tick + 1, 256))
    if not expected_ticks or expected_ticks[-1] != tick:
        expected_ticks.append(tick)
    require([row.get("tick") for row in milestones] == expected_ticks, "Missing or duplicate milestones")
    previous_births = previous_deaths = 0
    for row in milestones:
        integer(row.get("tick"), 1, tick, "milestone tick")
        counts = species_values(row.get("populationBySpecies"), 32, "milestone population")
        count = sum(counts.values())
        require(count <= maximum_population, "Milestone exceeds maximum population")
        born = integer(row.get("births"), previous_births, births, "milestone births")
        died = integer(row.get("deaths"), previous_deaths, deaths, "milestone deaths")
        require(count == 7 + born - died, "Milestone population accounting mismatch")
        previous_births, previous_deaths = born, died
        integer(row.get("maxGeneration"), 0, maximum_generation, "milestone generation")
        integer(row.get("evolvedOrganisms"), 0, min(count, case["maxEvolvedOrganisms"]), "milestone evolved")
        soil = integer(row.get("soilMatter"), 0, 1000, "milestone soil")
        detritus = integer(row.get("detritusMatter"), 0, 1000, "milestone detritus")
        require(soil + detritus <= 1000 and (count > 0) == (soil + detritus < 1000), "Milestone matter mismatch")
        for species in DEFAULTS:
            require(minima[species] <= counts.get(species, 0), "Milestone violates population minimum")
            if species in extinct:
                require((counts.get(species, 0) == 0) == (row["tick"] >= extinct[species]), "Milestone/extinction mismatch")
    for field in ("tick", "populationBySpecies", "soilMatter", "detritusMatter", "births", "deaths", "maxGeneration", "evolvedOrganisms"):
        require(milestones[-1][field] == final[field], f"Final milestone differs: {field}")


def validate_report(report: dict, plan: dict, prior: set[int]) -> dict:
    validate_plan(plan, prior)
    require(report.get("plan") == plan, "Report plan differs from predeclared sidecar")
    require(report.get("complete") is True, "Study is incomplete")
    cases = report.get("cases")
    require(type(cases) is list and len(cases) == plan["expectedCases"], "Missing or extra study cases")
    expected = {(profile, seed): index for profile in expected_profiles()
                for index, seed in enumerate(plan["seeds"][:plan["baselineSeeds"] if profile == "default" else plan["pairedSeeds"]])}
    indexed = {}
    for case in cases:
        require(type(case) is dict, "Invalid study case")
        key = (case.get("profile"), case.get("seed"))
        require(key in expected and key not in indexed, f"Unexpected or duplicate case {key}")
        validate_case(case, key[0], expected[key], plan)
        indexed[key] = case
    require(indexed.keys() == expected.keys(), "Missing planned cases")
    return indexed


def surviving(case: dict) -> bool:
    return not case["firstSpeciesExtinction"]


def summarize(primary: dict, plan: dict, comparison: dict, comparison_plan: dict, prior: dict) -> dict:
    prior_seeds = prior_seed_set(prior)
    cases = validate_report(primary, plan, prior_seeds)
    repeated = validate_report(comparison, comparison_plan, prior_seeds)
    require(comparison_plan["baselineSeeds"] <= plan["baselineSeeds"] and comparison_plan["pairedSeeds"] <= plan["pairedSeeds"], "Comparison exceeds primary study")
    for key, case in repeated.items():
        require(cases.get(key) == case, f"Cross-JDK result mismatch {key}")
    groups = {}
    for profile in expected_profiles():
        rows = [case for (name, _), case in cases.items() if name == profile]
        pairs = [case for case in rows if case["seed"] in plan["seeds"][:plan["pairedSeeds"]]]
        outcomes = {"bothSurvive": 0, "baselineOnlySurvives": 0, "perturbationOnlySurvives": 0, "neitherSurvives": 0}
        for case in pairs:
            baseline = surviving(cases[("default", case["seed"])])
            changed = surviving(case)
            key = "bothSurvive" if baseline and changed else "baselineOnlySurvives" if baseline else "perturbationOnlySurvives" if changed else "neitherSurvives"
            outcomes[key] += 1
        groups[profile] = {
            "cases": len(rows), "threeSpeciesAt1536": sum(surviving(case) for case in rows),
            "extinctionCases": [{"seed": case["seed"], "worldId": case["worldId"], "firstSpeciesExtinction": case["firstSpeciesExtinction"]} for case in rows if not surviving(case)],
            "minimumPopulationBySpecies": {species: min(case["minimumPopulationBySpecies"][species] for case in rows) for species in DEFAULTS},
            "generationBudgetReachedCases": sum(case["generationBudgetReached"] for case in rows),
            "birthBudgetReachedCases": sum(case["birthBudgetReached"] for case in rows),
            "birthsRange": [min(case["finalState"]["births"] for case in rows), max(case["finalState"]["births"] for case in rows)],
            "pairedBaselineCases": len(pairs), "pairedSurvivalOutcomes": outcomes,
        }
    return {"passed": True, "engineVersion": "genesis-ecology/2", "primaryCases": len(cases),
            "heldOutIndependentSeeds": plan["baselineSeeds"], "overlapWithPriorSeeds": 0,
            "profiles": groups, "crossJdkRepeatedCases": len(repeated),
            "crossJdkAddsIndependentSeeds": False, "excludedProfiles": plan["excludedProfiles"],
            "limits": ["Validates saved evidence structure and declared finite invariants; does not rerun physics or independently prove every per-step assertion.",
                       "All perturbations share a fixed baseline seed subset; case totals are not independent seed counts.",
                       "Finite 1536-tick samples cannot prove all-seed/all-parameter or indefinite coexistence; no production, human or performance acceptance."]}


def read_json(path: Path) -> dict:
    def no_duplicates(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=no_duplicates)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--primary", required=True, type=Path)
    parser.add_argument("--primary-plan", required=True, type=Path)
    parser.add_argument("--comparison", required=True, type=Path)
    parser.add_argument("--comparison-plan", required=True, type=Path)
    parser.add_argument("--prior-validation", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    paths = [args.primary, args.primary_plan, args.comparison, args.comparison_plan, args.prior_validation]
    try:
        result = summarize(*(read_json(path) for path in paths))
        result["inputHashes"] = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(result, stream, indent=2)
            stream.write("\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Study evidence rejected: {error}\n")
    print(json.dumps({"passed": True, "primaryCases": result["primaryCases"],
                      "heldOutIndependentSeeds": result["heldOutIndependentSeeds"],
                      "crossJdkRepeatedCases": result["crossJdkRepeatedCases"]}))


if __name__ == "__main__":
    main()
