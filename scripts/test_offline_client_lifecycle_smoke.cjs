"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { storageManifest, verifyStorage } = require("./offline_client_lifecycle_smoke.cjs");

test("a startup cannot silently change, drop, or add saved evidence", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "stargenesis-lifecycle-"));
  try {
    fs.mkdirSync(path.join(root, "ecology"));
    for (let i = 0; i < 6; i++) fs.writeFileSync(path.join(root, "ecology", `${i}.json`), JSON.stringify({ i }));
    const expected = storageManifest(root);
    verifyStorage(root, expected);
    const file = path.join(root, "ecology/0.json");
    fs.writeFileSync(file, "tampered");
    assert.throws(() => verifyStorage(root, expected), /changed persisted evidence/);
    fs.unlinkSync(file);
    assert.throws(() => verifyStorage(root, expected), /changed persisted evidence/);
    fs.writeFileSync(file, JSON.stringify({ i: 0 }));
    fs.writeFileSync(path.join(root, "ecology/extra.json"), "{}");
    assert.throws(() => verifyStorage(root, expected), /changed persisted evidence/);
    assert.throws(() => verifyStorage(root, {}), /Missing restart storage evidence/);
  } finally {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()));
    assert.ok(path.basename(root).startsWith("stargenesis-lifecycle-"));
    fs.rmSync(root, { recursive: true });
  }
});
