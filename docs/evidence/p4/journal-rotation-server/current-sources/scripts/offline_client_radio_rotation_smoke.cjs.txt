"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

function helperDirectory() {
  if (fs.existsSync(path.join(__dirname, "offline_client_workflow_smoke.cjs"))) return __dirname;
  let directory = __dirname;
  while (path.dirname(directory) !== directory) {
    const candidate = path.join(directory, "scripts");
    if (fs.existsSync(path.join(candidate, "offline_client_workflow_smoke.cjs"))) return candidate;
    directory = path.dirname(directory);
  }
  throw new Error("Cannot locate scripts/offline_client_workflow_smoke.cjs");
}

const { connect, command, commandUntil, disconnect } = require(path.join(helperDirectory(), "offline_client_workflow_smoke.cjs"));
const NAMES = { requester: "SGReview", provider: "SGRotatePeer", observer: "SGRotateWatch" };
const PINS = ["schemaVersion", "radioProtocolId", "radioProtocolHash", "serviceProtocolId", "serviceProtocolHash", "contractId", "contractHash"];
const MESSAGE_FIELDS = ["protocol", "protocolHash", "sender", "recipient", "tick", "sequence", "payload"];
const IDENTITY_FIELDS = ["protocol", "protocolHash", "sender", "sequence"];
const RADIO_EVENT_FIELDS = ["tick", "type", "protocol", "protocolHash", "actor", "peer", "sequence"];
const CONTRACT_EVENT_FIELDS = ["tick", "type", "contractId", "actor", "peer", "units", "state", "sequence"];
const read = file => JSON.parse(fs.readFileSync(file, "utf8"));
const sha256 = bytes => crypto.createHash("sha256").update(bytes).digest("hex");
const active = journal => journal.document.schemaVersion === 1 ? journal.document : journal.document.active;
const archiveName = generation => `radio-state.json.archives/segment-${String(generation).padStart(4, "0")}.json`;

function sameSnapshot(actual, expected) {
  validateSnapshotShape(actual);
  validateSnapshotShape(expected);
  assert.ok(Number.isSafeInteger(expected.radio.tick) && Number.isSafeInteger(actual.radio.tick)
    && actual.radio.tick >= expected.radio.tick, "Radio clock moved backwards");
  assert.equal(actual.radio.tick, actual.contracts.tick, "Radio and contract clocks diverged");
  const withoutClock = snapshot => ({ ...snapshot, radio: { ...snapshot.radio, tick: 0 }, contracts: { ...snapshot.contracts, tick: 0 } });
  assert.deepEqual(withoutClock(actual), withoutClock(expected), "Committed history or design pins changed");
}

function validateSnapshotShape(snapshot) {
  assert.deepEqual(Object.keys(snapshot).sort(), [...PINS, "radio", "contracts"].sort());
  assert.ok(Number.isSafeInteger(snapshot.schemaVersion) && snapshot.schemaVersion === 1);
  for (const field of PINS.slice(1)) assert.equal(typeof snapshot[field], "string");
  for (const field of ["radioProtocolHash", "serviceProtocolHash", "contractHash"])
    assert.match(snapshot[field], /^[0-9a-f]{64}$/);
  assert.deepEqual(Object.keys(snapshot.radio).sort(), ["tick", "evidence", "acknowledged", "pending", "events"].sort());
  assert.deepEqual(Object.keys(snapshot.contracts).sort(), ["tick", "states", "received", "events"].sort());
  for (const tick of [snapshot.radio.tick, snapshot.contracts.tick])
    assert.ok(Number.isSafeInteger(tick) && tick >= 0);
  for (const message of [...snapshot.radio.evidence, ...snapshot.contracts.received]) {
    assert.deepEqual(Object.keys(message).sort(), MESSAGE_FIELDS.slice().sort());
    assert.deepEqual(Object.keys(message.protocol).sort(), ["value"]);
    assert.equal(typeof message.protocol.value, "string");
    for (const field of ["protocolHash", "sender", "recipient"]) assert.equal(typeof message[field], "string");
    assert.ok(Number.isSafeInteger(message.tick) && message.tick >= 0);
    assert.ok(Number.isSafeInteger(message.sequence) && message.sequence >= 0);
    assert.ok(message.payload && typeof message.payload === "object" && !Array.isArray(message.payload));
    for (const [key, value] of Object.entries(message.payload)) assert.equal(typeof value, "string", key);
  }
  for (const identity of snapshot.radio.acknowledged) {
    assert.deepEqual(Object.keys(identity).sort(), IDENTITY_FIELDS.slice().sort());
    assert.deepEqual(Object.keys(identity.protocol).sort(), ["value"]);
    assert.ok(Number.isSafeInteger(identity.sequence) && identity.sequence >= 0);
  }
  for (const event of snapshot.radio.events) {
    assert.deepEqual(Object.keys(event).sort(), RADIO_EVENT_FIELDS.slice().sort());
    assert.deepEqual(Object.keys(event.protocol).sort(), ["value"]);
    for (const field of ["type", "protocolHash", "actor", "peer"]) assert.equal(typeof event[field], "string");
    assert.ok(Number.isSafeInteger(event.tick) && event.tick >= 0);
    assert.ok(Number.isSafeInteger(event.sequence) && event.sequence >= 0);
  }
  for (const contract of snapshot.contracts.states) {
    assert.deepEqual(Object.keys(contract).sort(), ["id", "requester", "provider", "requestedUnits", "fulfilledUnits", "createdAt", "expiresAt", "state"].sort());
    for (const field of ["id", "requester", "provider", "state"]) assert.equal(typeof contract[field], "string");
    for (const field of ["requestedUnits", "fulfilledUnits", "createdAt", "expiresAt"])
      assert.ok(Number.isSafeInteger(contract[field]) && contract[field] >= 0);
  }
  for (const event of snapshot.contracts.events) {
    assert.deepEqual(Object.keys(event).sort(), CONTRACT_EVENT_FIELDS.slice().sort());
    for (const field of ["type", "contractId", "actor", "peer", "state"]) assert.equal(typeof event[field], "string");
    for (const field of ["tick", "units", "sequence"]) assert.ok(Number.isSafeInteger(event[field]) && event[field] >= 0);
  }
}

function validateJournal(journal) {
  const document = journal.document;
  assert.ok(Number.isSafeInteger(document.schemaVersion) && (document.schemaVersion === 1 || document.schemaVersion === 2), "Unexpected journal version");
  assert.deepEqual(Object.keys(document).sort(), (document.schemaVersion === 1 ? [...PINS, "radio", "contracts"] : ["schemaVersion", "active", "archives"]).sort());
  const references = document.schemaVersion === 1 ? [] : document.archives;
  assert.ok(Array.isArray(references) && references.length <= 16);
  if (document.schemaVersion === 2) assert.ok(references.length > 0);
  const expectedFiles = ["radio-state.json"];
  let lowerTick = 0;
  for (const [index, reference] of references.entries()) {
    assert.deepEqual(Object.keys(reference).sort(), ["bytes", "generation", "sha256"]);
    assert.ok(Number.isSafeInteger(reference.generation) && reference.generation === index + 1);
    assert.ok(Number.isSafeInteger(reference.bytes) && reference.bytes >= 1 && reference.bytes <= 16 * 1024 * 1024);
    assert.match(reference.sha256, /^[0-9a-f]{64}$/);
    const name = archiveName(reference.generation);
    expectedFiles.push(name);
    assert.deepEqual(journal.files[name], { bytes: reference.bytes, sha256: reference.sha256 }, "Manifest archive pin differs from committed bytes");
    const snapshot = journal.archives[name];
    validateSnapshotShape(snapshot);
    assert.equal(snapshot.schemaVersion, 1);
    assert.equal(snapshot.radio.tick, snapshot.contracts.tick);
    assert.ok(snapshot.radio.tick >= lowerTick);
    assert.ok(snapshot.radio.evidence.length > 0);
    assert.equal(snapshot.radio.pending.length, 0);
    assert.ok(snapshot.contracts.states.every(state => ["COMPLETED", "REJECTED", "CANCELLED", "EXPIRED"].includes(state.state)));
    lowerTick = snapshot.radio.tick;
  }
  assert.deepEqual(Object.keys(journal.files).sort(), expectedFiles.sort(), "Committed journal file inventory changed");
  assert.deepEqual(Object.keys(journal.archives).sort(), expectedFiles.filter(name => name !== "radio-state.json").sort());
  const snapshot = active(journal);
  validateSnapshotShape(snapshot);
  assert.equal(snapshot.schemaVersion, 1);
  assert.equal(snapshot.radio.tick, snapshot.contracts.tick);
  assert.ok(snapshot.radio.tick >= lowerTick);
  return snapshot;
}

function readJournal(directory) {
  const stateFile = path.join(directory, "radio-state.json");
  assert.ok(fs.lstatSync(stateFile).isFile() && !fs.lstatSync(stateFile).isSymbolicLink());
  const bytes = fs.readFileSync(stateFile);
  const journal = { document: JSON.parse(bytes.toString("utf8")), files: { "radio-state.json": { bytes: bytes.length, sha256: sha256(bytes) } }, archives: {} };
  const archiveDirectory = path.join(directory, "radio-state.json.archives");
  if (fs.existsSync(archiveDirectory)) {
    assert.ok(fs.lstatSync(archiveDirectory).isDirectory() && !fs.lstatSync(archiveDirectory).isSymbolicLink());
    for (const entry of fs.readdirSync(archiveDirectory).sort()) {
      assert.match(entry, /^segment-\d{4}\.json$/, "Unexpected archive entry after a successful phase");
      const file = path.join(archiveDirectory, entry);
      assert.ok(fs.lstatSync(file).isFile() && !fs.lstatSync(file).isSymbolicLink());
      const archived = fs.readFileSync(file);
      const relative = `radio-state.json.archives/${entry}`;
      journal.files[relative] = { bytes: archived.length, sha256: sha256(archived) };
      journal.archives[relative] = JSON.parse(archived.toString("utf8"));
    }
  }
  validateJournal(journal);
  return journal;
}

function sameCommittedJournal(actual, expected) {
  validateJournal(actual);
  validateJournal(expected);
  assert.equal(actual.document.schemaVersion, expected.document.schemaVersion);
  assert.deepEqual(actual.document.archives, expected.document.archives, "Restart changed committed archive references");
  assert.deepEqual(actual.archives, expected.archives, "Restart rewrote archived history");
  for (const name of Object.keys(expected.files).filter(name => name !== "radio-state.json"))
    assert.deepEqual(actual.files[name], expected.files[name], "Restart changed archive bytes");
  sameSnapshot(active(actual), active(expected));
}

function validatePhaseJournal(journal, phase, identities, previous) {
  const snapshot = validateJournal(journal);
  const radio = snapshot.radio;
  const references = journal.document.archives || [];
  const expectedArchives = { prepare: 0, rotate: 1, verify: 2 }[phase];
  assert.equal(references.length, expectedArchives);
  assert.equal(journal.document.schemaVersion, phase === "prepare" ? 1 : 2);
  assert.equal(snapshot.radio.pending.length, 0);
  assert.equal(snapshot.radio.acknowledged.length, snapshot.radio.evidence.length);
  assert.equal(snapshot.radio.events.length, snapshot.radio.evidence.length * 2);
  const requester = identities.requester;
  const provider = identities.provider;
  const specifications = phase === "prepare" ? [
    ["genesis:radio", requester, provider, 1001, { sequence: "1001", channel: "rotation", value: "rotation_payload" }],
    ["genesis:service", requester, provider, 1002, { contract: "rotation_order", action: "offer", units: "3" }],
    ["genesis:service", requester, provider, 1003, { contract: "rotation_order", action: "cancel", units: "0" }],
  ] : phase === "rotate" ? [
    ["genesis:radio", provider, requester, 2001, { sequence: "2001", channel: "rotation", value: "next_segment_payload" }],
  ] : [
    ["genesis:radio", requester, provider, 3001, { sequence: "3001", channel: "rotation", value: "after_second_rotation" }],
  ];
  const protocolHashes = { "genesis:radio": snapshot.radioProtocolHash, "genesis:service": snapshot.serviceProtocolHash };
  const expectedMessages = new Map();
  for (const [protocol, sender, recipient, sequence, payload] of specifications) {
    const message = radio.evidence.find(item => item.sender === sender && item.sequence === sequence);
    assert.ok(message);
    assert.deepEqual(message.protocol, { value: protocol });
    assert.equal(message.protocolHash, protocolHashes[protocol]);
    assert.equal(message.recipient, recipient);
    assert.deepEqual(message.payload, payload);
    assert.ok(Number.isSafeInteger(message.tick) && message.tick <= radio.tick);
    expectedMessages.set(`${protocol}:${sender}:${sequence}`, message);
  }
  assert.equal(expectedMessages.size, specifications.length);
  assert.equal(radio.evidence.length, specifications.length);
  const expectedAcknowledgements = [...expectedMessages.values()].map(message => ({ protocol: message.protocol,
    protocolHash: message.protocolHash, sender: message.sender, sequence: message.sequence }))
    .sort((first, second) => first.protocol.value.localeCompare(second.protocol.value)
      || first.protocolHash.localeCompare(second.protocolHash) || first.sender.localeCompare(second.sender)
      || first.sequence - second.sequence);
  assert.deepEqual(radio.acknowledged, expectedAcknowledgements);
  const expectedRadioEvents = [];
  let previousEventTick = -1;
  for (const [protocol, sender, recipient, sequence] of specifications) {
    const message = expectedMessages.get(`${protocol}:${sender}:${sequence}`);
    const ackEvent = radio.events.find(event => event.type === "radio.acknowledged" && event.actor === recipient
      && event.peer === sender && event.protocol.value === protocol && event.sequence === sequence);
    assert.ok(ackEvent);
    assert.ok(Number.isSafeInteger(ackEvent.tick) && ackEvent.tick >= message.tick + 1 && ackEvent.tick <= radio.tick);
    expectedRadioEvents.push({ tick: message.tick, type: "radio.sent", protocol: message.protocol,
      protocolHash: message.protocolHash, actor: sender, peer: recipient, sequence });
    expectedRadioEvents.push({ tick: ackEvent.tick, type: "radio.acknowledged", protocol: message.protocol,
      protocolHash: message.protocolHash, actor: recipient, peer: sender, sequence });
  }
  for (const event of radio.events) {
    assert.ok(Number.isSafeInteger(event.tick) && event.tick >= previousEventTick);
    previousEventTick = event.tick;
  }
  assert.deepEqual(radio.events, expectedRadioEvents);
  if (phase === "prepare") {
    assert.equal(snapshot.contracts.states.length, 1);
    const contract = snapshot.contracts.states[0];
    assert.equal(contract.id, "rotation_order");
    assert.equal(contract.state, "CANCELLED");
    assert.equal(contract.requester, identities.requester);
    assert.equal(contract.provider, identities.provider);
    assert.equal(contract.requestedUnits, 3);
    assert.equal(contract.fulfilledUnits, 0);
    assert.equal(snapshot.contracts.received.length, 2);
    assert.equal(snapshot.contracts.events.length, 2);
    const expectedReceived = [1002, 1003].map(sequence => expectedMessages.get(`genesis:service:${requester}:${sequence}`));
    assert.deepEqual(snapshot.contracts.received, expectedReceived);
    const expectedContractEvents = [["contract.offer", 1002, "OFFERED", 3], ["contract.cancel", 1003, "CANCELLED", 0]];
    let previousContractTick = -1;
    for (let index = 0; index < expectedContractEvents.length; index += 1) {
      const [type, sequence, state, units] = expectedContractEvents[index];
      const message = expectedReceived[index];
      const event = snapshot.contracts.events[index];
      assert.ok(Number.isSafeInteger(event.tick) && event.tick >= message.tick + 1 && event.tick <= snapshot.contracts.tick);
      assert.ok(event.tick >= previousContractTick);
      previousContractTick = event.tick;
      assert.deepEqual(event, { tick: event.tick, type, contractId: "rotation_order", actor: requester, peer: provider,
        units, state, sequence });
    }
  } else {
    assert.ok(previous, "Rotation needs previous shutdown evidence");
    validateJournal(previous);
    for (const name of Object.keys(previous.archives)) {
      assert.deepEqual(journal.files[name], previous.files[name], "Previously archived bytes changed");
      assert.deepEqual(journal.archives[name], previous.archives[name]);
    }
    const appended = journal.archives[archiveName(expectedArchives)];
    sameSnapshot(appended, active(previous));
    for (const pin of PINS) assert.deepEqual(snapshot[pin], appended[pin]);
    assert.deepEqual(snapshot.contracts.states, []);
    assert.deepEqual(snapshot.contracts.received, []);
    assert.deepEqual(snapshot.contracts.events, []);
  }
}

function copyJournal(directory, destination) {
  const journal = readJournal(directory);
  assert.ok(!fs.existsSync(destination), "Client evidence directory already exists");
  fs.mkdirSync(destination, { recursive: true });
  for (const name of Object.keys(journal.files)) {
    const target = path.join(destination, name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.copyFileSync(path.join(directory, name), target, fs.constants.COPYFILE_EXCL);
  }
  const copied = readJournal(destination);
  sameCommittedJournal(copied, journal);
  sameCommittedJournal(readJournal(directory), copied);
  return copied;
}

async function acknowledgeRegular(bot, expected) {
  const line = await commandUntil(bot, "radio inbox", expected);
  const match = line.match(/from=([^\s]+).*?tick=(\d+) · seq=(\d+)/);
  assert.ok(match, "Regular inbox output lacks an acknowledgement identity");
  await command(bot, `radio ack ${match[1]} ${match[2]} ${match[3]}`, "消息已确认");
  return { sender: match[1], tick: Number(match[2]), sequence: Number(match[3]) };
}

async function unchangedAfter(bot, value, expected, directory) {
  const before = readJournal(directory);
  const response = await command(bot, value, expected);
  sameCommittedJournal(readJournal(directory), before);
  return response;
}

async function main() {
  const options = {};
  for (let index = 2; index < process.argv.length; index += 2)
    options[process.argv[index].replace(/^--/, "")] = process.argv[index + 1];
  for (const key of ["data-directory", "module-root", "output", "host", "port", "kind"])
    assert.ok(options[key], `Missing --${key}`);
  const directory = options["data-directory"];
  const parent = path.dirname(directory);
  const input = read(path.join(parent, "radio-rotation-input.json"));
  assert.ok(["prepare", "rotate", "verify"].includes(input.phase));
  const stateFile = path.join(parent, "radio-rotation-client-state.json");
  const previous = input.previousDirectory ? readJournal(input.previousDirectory) : null;
  const names = Object.fromEntries(Object.entries(NAMES).filter(([role]) => role !== "observer" || input.withObserver));
  const result = { passed: false, kind: options.kind, phase: input.phase, players: Object.keys(names).length,
    humanAcceptance: false, checks: [] };
  const bots = {};
  try {
    if (previous) sameCommittedJournal(readJournal(directory), previous);
    const mineflayer = require(path.join(path.resolve(options["module-root"]), "mineflayer"));
    for (const [role, name] of Object.entries(names)) bots[role] = await connect(mineflayer, options.host, Number(options.port), name);
    result.identities = Object.fromEntries(Object.entries(bots).map(([role, bot]) => [role, bot.player.uuid]));
    let state;
    if (input.phase === "prepare") {
      assert.ok(!fs.existsSync(stateFile), "Prepare needs fresh client state");
      state = { identities: result.identities };
      for (const role of ["requester", "provider"]) {
        await command(bots[role], "radio chip genesis:radio", "已生成绑定 genesis:radio");
        await command(bots[role], "radio chip genesis:service", "已生成绑定 genesis:service");
      }
    } else {
      state = read(stateFile);
      assert.deepEqual(result.identities, state.identities, "Offline player identities changed");
      await command(bots.provider, "radio status", "participants=0/64");
      await command(bots.provider, "radio inbox", "Participant is not registered");
      sameCommittedJournal(readJournal(directory), previous);
      result.checks.push("restart_preserves_committed_bytes_history_and_fixed_identities_without_online_endpoints");
    }
    for (const role of ["requester", "provider"])
      await command(bots[role], "radio join rotation 0 0 0 16", "已加入虚拟无线网络");
    await unchangedAfter(bots.provider, "radio rotate", "permission denied (stargenesis.radio.manage)", directory);
    result.checks.push("non_operator_rotation_denied_without_history_changes");
    const requester = result.identities.requester;
    const provider = result.identities.provider;
    const original = `radio send ${provider} 1001 rotation rotation_payload`;
    if (input.phase === "prepare") {
      await command(bots.requester, original, "消息已排入");
      await unchangedAfter(bots.requester, "radio rotate", "Resolve every pending message and active contract before rotation", directory);
      result.checks.push("pending_message_blocks_operator_rotation_without_history_changes");
      state.originalIdentity = await acknowledgeRegular(bots.provider, "rotation_payload");
      await command(bots.requester, `radio send contract ${provider} 1002 rotation_order offer 3`, "消息已排入");
      await commandUntil(bots.provider, "radio inbox", "rotation_order · OFFERED");
      assert.equal(active(readJournal(directory)).radio.pending.length, 0);
      await unchangedAfter(bots.requester, "radio rotate", "Resolve every pending message and active contract before rotation", directory);
      result.checks.push("active_contract_blocks_operator_rotation_after_offer_acknowledgement");
      await command(bots.requester, `radio send contract ${provider} 1003 rotation_order cancel 0`, "消息已排入");
      await commandUntil(bots.provider, "radio inbox", "rotation_order · CANCELLED");
      await command(bots.requester, "radio contract rotation_order", "CANCELLED · units=0/3");
      result.checks.push("all_messages_acknowledged_and_terminal_contract_saved_before_first_restart");
    } else {
      await command(bots.requester, "radio contract rotation_order", "CANCELLED · units=0/3");
      const beforeRotation = readJournal(directory);
      const number = input.phase === "rotate" ? 1 : 2;
      await command(bots.requester, "radio rotate", `无线日志已归档为第 ${number} 段`);
      const rotated = readJournal(directory);
      assert.equal(rotated.document.archives.length, number);
      sameSnapshot(rotated.archives[archiveName(number)], active(beforeRotation));
      assert.equal(active(rotated).radio.evidence.length, 0);
      assert.equal(active(rotated).contracts.states.length, 0);
      await command(bots.requester, "radio status", "participants=2/64");
      result.checks.push("operator_rotation_atomically_archives_quiescent_history_and_preserves_online_endpoints");
      await unchangedAfter(bots.requester, original, "重复消息已识别", directory);
      await unchangedAfter(bots.requester, `radio send ${provider} 1001 rotation changed_payload`, "Archived message sequence reused with different content", directory);
      await unchangedAfter(bots.requester, `radio send contract ${provider} 1099 rotation_order offer 3`, "Archived contract identity cannot be reused", directory);
      result.checks.push("archived_duplicate_conflict_and_contract_identity_reuse_keep_history_unchanged");
      if (input.phase === "rotate") {
        await command(bots.provider, `radio send ${requester} 2001 rotation next_segment_payload`, "消息已排入");
        await acknowledgeRegular(bots.requester, "next_segment_payload");
      } else {
        await unchangedAfter(bots.provider, `radio send ${requester} 2001 rotation next_segment_payload`, "重复消息已识别", directory);
        await unchangedAfter(bots.provider, `radio send ${requester} 2001 rotation changed_payload`, "Archived message sequence reused with different content", directory);
        await command(bots.requester, `radio send ${provider} 3001 rotation after_second_rotation`, "消息已排入");
        await acknowledgeRegular(bots.provider, "after_second_rotation");
        result.checks.push("first_archive_contract_survives_restart_and_second_rotation_preserves_second_segment_identity");
      }
      await command(bots.requester, "radio contract rotation_order", "CANCELLED · units=0/3");
      if (bots.observer) {
        await unchangedAfter(bots.observer, "radio contract rotation_order", "只有合同参与者可以查看状态", directory);
        await unchangedAfter(bots.observer, "radio rotate", "permission denied (stargenesis.radio.manage)", directory);
        result.checks.push("third_party_cannot_query_archived_contract_or_rotate");
      }
      result.checks.push("new_segment_delivery_and_acknowledgement_work_without_rejoining");
    }
    fs.writeFileSync(stateFile, JSON.stringify(state, null, 2) + "\n", "utf8");
    result.journalAtFinish = copyJournal(directory, input.clientEvidenceDirectory);
    validatePhaseJournal(result.journalAtFinish, input.phase, result.identities, previous);
    result.passed = true;
  } catch (error) { result.error = error.stack || String(error); }
  finally {
    await Promise.all(Object.values(bots).map(disconnect));
    fs.writeFileSync(options.output, JSON.stringify(result, null, 2) + "\n", "utf8");
  }
  console.log(JSON.stringify(result, null, 2));
  process.exit(result.passed ? 0 : 1);
}

module.exports = { active, archiveName, readJournal, sameSnapshot, sameCommittedJournal, validateJournal, validatePhaseJournal };
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
