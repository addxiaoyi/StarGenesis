"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { manifest, verifyRestart, assertReorganized, assertCompensation } = require("./offline_client_match_admin_smoke.cjs");

test("compensation reads the organization balance row even when the auction also names its winner", () => {
  const lines = ["工厂 7/100 · auction=SETTLED · winner=org_cedar",
    "org_cedar members=[alice, bob] · credits=85 · ore=16 · parts=0",
    "org_ember members=[carol, dave] · credits=100 · ore=0 · parts=0"];
  assertCompensation(lines, "org_cedar");
  assert.throws(() => assertCompensation(lines.map((line) => line.replace("credits=85", "credits=84")), "org_cedar"), /Compensation balances/);
  assert.throws(() => assertCompensation(lines.slice(0, 1), "org_cedar"), /Compensation balances/);
});

test("match restart evidence rejects resurrection, lost files and modified published rules", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stargenesis-match-admin-"));
  try {
    for (const directory of ["factory", "arena-matches", "arenas/published"]) fs.mkdirSync(path.join(root, directory), { recursive: true });
    for (const file of ["factory/organizations.json", "factory/new.factory.json", "arena-matches/teams.json",
      "arena-matches/new.match.json", "arenas/draft.json", "arenas/published/ruleset.json"])
      fs.writeFileSync(path.join(root, file), "{}");
    const expected = manifest(root);
    verifyRestart(root, expected);
    const removed = path.join(root, "factory/new.factory.json");
    fs.unlinkSync(removed);
    assert.throws(() => verifyRestart(root, expected), /Restart changed/);
    fs.writeFileSync(removed, "{}");
    const resurrected = path.join(root, "factory/old.factory.json");
    fs.writeFileSync(resurrected, "{}");
    assert.throws(() => verifyRestart(root, expected), /Restart changed/);
    fs.unlinkSync(resurrected);
    fs.writeFileSync(path.join(root, "arenas/published/ruleset.json"), '{"changed":true}');
    assert.throws(() => verifyRestart(root, expected), /Restart changed/);
    assert.throws(() => verifyRestart(root, {}), /Missing match lifecycle/);
  } finally {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()));
    assert.ok(path.basename(root).startsWith("stargenesis-match-admin-"));
    fs.rmSync(root, { recursive: true });
  }
});

test("reorganized rosters must contain exactly the new pairs and teams", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stargenesis-match-admin-"));
  try {
    fs.mkdirSync(path.join(root, "factory"));
    fs.mkdirSync(path.join(root, "arena-matches"));
    const ids = { alice: "a", bob: "b", carol: "c", dave: "d" };
    const pairs = { schemaVersion: 1, pairs: [{ first: "c", second: "a" }, { first: "b", second: "d" }] };
    const teams = { schemaVersion: 1, teams: { a: "cedar", b: "ember", c: "cedar", d: "ember" } };
    fs.writeFileSync(path.join(root, "factory/organizations.json"), JSON.stringify(pairs));
    fs.writeFileSync(path.join(root, "arena-matches/teams.json"), JSON.stringify(teams));
    assertReorganized(root, ids);
    pairs.pairs[0].second = "b";
    fs.writeFileSync(path.join(root, "factory/organizations.json"), JSON.stringify(pairs));
    assert.throws(() => assertReorganized(root, ids), /Organization roster/);
  } finally {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()));
    assert.ok(path.basename(root).startsWith("stargenesis-match-admin-"));
    fs.rmSync(root, { recursive: true });
  }
});
