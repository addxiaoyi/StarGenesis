"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { active, archiveName, readJournal, sameCommittedJournal, validateJournal, validatePhaseJournal } = require("./offline_client_radio_rotation_smoke.cjs");
const RADIO_HASH = "a".repeat(64);
const SERVICE_HASH = "b".repeat(64);

function snapshot(sequence = 1001, sender = "requester", contract = false, tick = 10) {
  const recipient = sender === "requester" ? "provider" : "requester";
  const specifications = contract ? [
    ["genesis:radio", RADIO_HASH, 1001, { sequence: "1001", channel: "rotation", value: "rotation_payload" }],
    ["genesis:service", SERVICE_HASH, 1002, { contract: "rotation_order", action: "offer", units: "3" }],
    ["genesis:service", SERVICE_HASH, 1003, { contract: "rotation_order", action: "cancel", units: "0" }],
  ] : [["genesis:radio", RADIO_HASH, sequence, { sequence: String(sequence), channel: "rotation",
    value: sequence === 2001 ? "next_segment_payload" : sequence === 3001 ? "after_second_rotation" : "rotation_payload" }]];
  const evidence = specifications.map(([protocol, protocolHash, value, payload], index) => ({ protocol: { value: protocol },
    protocolHash, sender, recipient, tick: index * 2 + 1, sequence: value, payload }));
  const events = evidence.flatMap(message => [
    { tick: message.tick, type: "radio.sent", protocol: message.protocol, protocolHash: message.protocolHash,
      actor: message.sender, peer: message.recipient, sequence: message.sequence },
    { tick: message.tick + 1, type: "radio.acknowledged", protocol: message.protocol, protocolHash: message.protocolHash,
      actor: message.recipient, peer: message.sender, sequence: message.sequence },
  ]);
  return { schemaVersion: 1, radioProtocolId: "genesis:radio", radioProtocolHash: RADIO_HASH,
    serviceProtocolId: "genesis:service", serviceProtocolHash: SERVICE_HASH, contractId: "genesis:service_contract", contractHash: "c".repeat(64),
    radio: { tick, evidence, acknowledged: evidence.map(message => ({ protocol: message.protocol,
      protocolHash: message.protocolHash, sender: message.sender, sequence: message.sequence })), pending: [], events },
    contracts: { tick, states: contract ? [{ id: "rotation_order", state: "CANCELLED", requester: "requester", provider: "provider", requestedUnits: 3, fulfilledUnits: 0 }] : [],
      received: contract ? structuredClone(evidence.slice(1)) : [],
      events: contract ? [{ tick: 5, type: "contract.offer", contractId: "rotation_order", actor: "requester", peer: "provider", units: 3, state: "OFFERED", sequence: 1002 },
        { tick: 7, type: "contract.cancel", contractId: "rotation_order", actor: "requester", peer: "provider", units: 0, state: "CANCELLED", sequence: 1003 }] : [] } };
}

function terminalSnapshot(sequence = 1001) {
  const value = snapshot(sequence, "requester", true);
  value.contracts.states[0].createdAt = 5;
  value.contracts.states[0].expiresAt = 205;
  return value;
}

function bundle(current, archives = []) {
  const files = {};
  const stored = {};
  const references = archives.map((archived, index) => {
    const bytes = Buffer.from(JSON.stringify(archived));
    const name = archiveName(index + 1);
    files[name] = { bytes: bytes.length, sha256: crypto.createHash("sha256").update(bytes).digest("hex") };
    stored[name] = structuredClone(archived);
    return { generation: index + 1, ...files[name] };
  });
  const document = archives.length ? { schemaVersion: 2, active: current, archives: references } : current;
  const bytes = Buffer.from(JSON.stringify(document));
  files["radio-state.json"] = { bytes: bytes.length, sha256: crypto.createHash("sha256").update(bytes).digest("hex") };
  return { document: structuredClone(document), files, archives: stored };
}

test("three phase validator requires complete immutable prefixes and the next active identity", () => {
  const prepare = bundle(terminalSnapshot());
  const rotate = bundle(snapshot(2001, "provider", false, 20), [active(prepare)]);
  const verify = bundle(snapshot(3001, "requester", false, 30), [active(prepare), active(rotate)]);
  const identities = { requester: "requester", provider: "provider" };
  validatePhaseJournal(prepare, "prepare", identities, null);
  validatePhaseJournal(rotate, "rotate", identities, prepare);
  validatePhaseJournal(verify, "verify", identities, rotate);
  for (const mutate of [doc => active(doc).radio.evidence[0].sequence++, doc => active(doc).radio.pending.push({ sequence: 3001 }),
    doc => doc.archives[archiveName(2)].radio.evidence[0].sequence++, doc => doc.files[archiveName(1)].sha256 = "0".repeat(64)]) {
    const invalid = structuredClone(verify);
    mutate(invalid);
    assert.throws(() => validatePhaseJournal(invalid, "verify", identities, rotate));
  }
});

test("shutdown permits synchronized clock progress and rejects all other history changes", () => {
  const before = bundle(snapshot(2001, "provider", false, 20), [terminalSnapshot()]);
  const advanced = structuredClone(before);
  active(advanced).radio.tick = active(advanced).contracts.tick = 24;
  sameCommittedJournal(advanced, before);
  for (const mutate of [doc => active(doc).radio.tick = 19, doc => active(doc).contracts.tick++,
    doc => active(doc).radio.evidence[0].sequence++, doc => active(doc).radio.acknowledged.pop(),
    doc => active(doc).radio.events.reverse(), doc => active(doc).contractHash = "changed",
    doc => doc.document.unexpected = true, doc => doc.archives[archiveName(1)].contracts.received.pop()]) {
    const invalid = structuredClone(advanced);
    mutate(invalid);
    assert.throws(() => sameCommittedJournal(invalid, before));
  }
});

test("manifest validation rejects missing files, changed hashes, generation gaps and extra files", () => {
  const before = bundle(snapshot(2001, "provider", false, 20), [terminalSnapshot()]);
  for (const mutate of [doc => delete doc.files[archiveName(1)], doc => delete doc.archives[archiveName(1)],
    doc => doc.document.archives[0].generation++, doc => doc.document.archives[0].bytes++,
    doc => doc.files[archiveName(1)].sha256 = "0".repeat(64), doc => doc.files[archiveName(3)] = { bytes: 1, sha256: "0".repeat(64) }]) {
    const invalid = structuredClone(before);
    mutate(invalid);
    assert.throws(() => validateJournal(invalid));
  }
});

test("file reader compares manifest pins to real archived bytes", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "radio-rotation-validator-"));
  try {
    const journal = bundle(snapshot(2001, "provider", false, 20), [terminalSnapshot()]);
    fs.writeFileSync(path.join(directory, "radio-state.json"), JSON.stringify(journal.document));
    const archived = path.join(directory, archiveName(1));
    fs.mkdirSync(path.dirname(archived));
    const bytes = JSON.stringify(journal.archives[archiveName(1)]);
    fs.writeFileSync(archived, bytes);
    validateJournal(readJournal(directory));
    fs.writeFileSync(archived, bytes.replace("CANCELLED", "COMPLETED"));
    assert.throws(() => readJournal(directory));
    fs.unlinkSync(archived);
    assert.throws(() => readJournal(directory));
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
