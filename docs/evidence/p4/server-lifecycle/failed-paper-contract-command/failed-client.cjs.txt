"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { pause, connect, command, commandUntil, disconnect, dropConnection } = require("./offline_client_workflow_smoke.cjs");

const read = file => JSON.parse(fs.readFileSync(file, "utf8"));
const names = { requester: "SGRadioRequest", provider: "SGRadioProvide" };

function verifyRedelivery(line, identity) {
  const match = line.match(/from=([^\s]+).*?tick=(\d+) · seq=(\d+)/);
  assert.ok(match && line.includes("retained_payload") && line.includes("supply"));
  assert.deepEqual({ sender: match[1], tick: Number(match[2]), sequence: Number(match[3]) }, identity);
}

function sameCommittedRadio(actual, expected) {
  for (const key of ["schemaVersion", "radioProtocolId", "radioProtocolHash", "serviceProtocolId",
    "serviceProtocolHash", "contractId", "contractHash"]) assert.deepEqual(actual[key], expected[key], key);
  assert.ok(actual.radio.tick >= expected.radio.tick, "Radio clock moved backwards");
  assert.equal(actual.radio.tick, actual.contracts.tick, "Clocks diverged");
  for (const key of ["evidence", "acknowledged", "pending", "events"])
    assert.deepEqual(actual.radio[key], expected.radio[key], `Restart changed radio ${key}`);
  assert.deepEqual(actual.contracts.received, expected.contracts.received, "Restart applied unclaimed contract input");
  assert.deepEqual(actual.contracts.events.slice(0, expected.contracts.events.length), expected.contracts.events,
    "Restart rewrote contract history");
  assert.equal(actual.contracts.states.length, expected.contracts.states.length);
  for (const before of expected.contracts.states) {
    const after = actual.contracts.states.find(state => state.id === before.id);
    assert.ok(after);
    assert.deepEqual({ ...after, state: before.state }, before, "Restart changed contract content");
    assert.ok(after.state === before.state || (["OFFERED", "ACCEPTED"].includes(before.state) && after.state === "EXPIRED"
      && actual.contracts.tick > before.expiresAt), "Invalid restart transition");
  }
  const expectedExpirations = expected.contracts.states.filter(state => ["OFFERED", "ACCEPTED"].includes(state.state)
    && actual.contracts.tick > state.expiresAt).map(state => ({ tick: state.expiresAt + 1, type: "contract.expire",
      contractId: state.id, actor: state.provider, peer: state.requester, units: 0, state: "EXPIRED", sequence: -1 }));
  expectedExpirations.sort((a, b) => a.tick - b.tick || a.contractId.localeCompare(b.contractId));
  assert.deepEqual(actual.contracts.events.slice(expected.contracts.events.length), expectedExpirations);
}

async function main() {
  const options = {};
  for (let i = 2; i < process.argv.length; i += 2) options[process.argv[i].replace(/^--/, "")] = process.argv[i + 1];
  const data = options["data-directory"];
  const input = read(path.join(path.dirname(data), "radio-lifecycle-input.json"));
  const stateFile = path.join(path.dirname(data), "radio-lifecycle-client-state.json");
  const radioFile = path.join(data, "radio-state.json");
  const result = { passed: false, kind: options.kind, phase: input.phase, players: 2, humanAcceptance: false, checks: [] };
  const bots = {};
  try {
    const mineflayer = require(path.join(path.resolve(options["module-root"]), "mineflayer"));
    if (input.previousSnapshot) sameCommittedRadio(read(radioFile), read(input.previousSnapshot));
    for (const [role, name] of Object.entries(names))
      bots[role] = await connect(mineflayer, options.host, Number(options.port), name);
    result.identities = Object.fromEntries(Object.entries(bots).map(([role, bot]) => [role, bot.player.uuid]));
    let state;
    if (input.phase === "prepare") {
      assert.ok(!fs.existsSync(stateFile), "Prepare must use fresh state");
      state = { identities: result.identities };
      for (const bot of Object.values(bots)) {
        await command(bot, "radio chip genesis:radio", "已生成绑定 genesis:radio");
        await command(bot, "radio chip genesis:service", "已生成绑定 genesis:service");
      }
    } else {
      state = read(stateFile);
      assert.deepEqual(result.identities, state.identities);
      await command(bots.provider, "radio status", "participants=0/64");
      await command(bots.provider, "radio inbox", "Participant is not registered");
      sameCommittedRadio(read(radioFile), read(input.previousSnapshot));
      result.checks.push("fixed_identities_and_unclaimed_radio_history_survive_restart_without_online_endpoints");
    }
    for (const bot of Object.values(bots))
      await command(bot, "radio join recovery 0 0 0 16", "已加入虚拟无线网络");
    const requester = result.identities.requester;
    const provider = result.identities.provider;
    const regular = `radio send ${provider} 10 supply retained_payload`;
    const offer = `radio send contract ${provider} 11 restart_order offer 3`;

    if (input.phase === "prepare") {
      await command(bots.requester, regular, "消息已排入");
      const line = await commandUntil(bots.provider, "radio inbox", "retained_payload");
      const match = line.match(/from=([^\s]+).*?tick=(\d+) · seq=(\d+)/);
      assert.ok(match);
      state.regularIdentity = { sender: match[1], tick: Number(match[2]), sequence: Number(match[3]) };
      state.regularLine = line;
      await command(bots.requester, offer, "消息已排入");
      const saved = read(radioFile);
      assert.equal(saved.radio.pending.length, 2);
      assert.equal(saved.contracts.states.length, 0);
      result.checks.push("regular_poll_remains_unacknowledged_and_unclaimed_offer_is_durable_before_shutdown");
    } else if (input.phase === "resume") {
      // Finish this contract before its fixed 200 logical tick deadline; persistence assertions below read files directly.
      await command(bots.provider, "radio inbox", "restart_order · OFFERED");
      const redelivered = bots.provider.messages.find(line => line.includes("retained_payload"));
      assert.ok(redelivered, "Original radio envelope was not redelivered");
      verifyRedelivery(redelivered, state.regularIdentity);
      await command(bots.provider, `radio send contract ${requester} 21 restart_order accept 3`, "消息已排入");
      await commandUntil(bots.requester, "radio inbox", "restart_order · ACCEPTED");
      await command(bots.provider, `radio send contract ${requester} 22 restart_order fulfill 3`, "消息已排入");
      await commandUntil(bots.requester, "radio inbox", "restart_order · COMPLETED");
      let saved = read(radioFile);
      assert.equal(saved.contracts.states.find(item => item.id === "restart_order").fulfilledUnits, 3);
      assert.equal(saved.contracts.received.length, 3);
      assert.equal(saved.contracts.events.length, 3);
      result.checks.push("pending_offer_recovers_then_accept_and_fulfill_commit_once_with_radio_acknowledgements");

      await command(bots.requester, offer, "重复消息已识别");
      await command(bots.requester, `radio send contract ${provider} 11 restart_order offer 4`, "sequence reused with different content");
      verifyRedelivery(await command(bots.provider, "radio inbox", "retained_payload"), state.regularIdentity);
      const original = state.regularIdentity;
      await command(bots.provider, `radio ack ${original.sender} ${original.tick} ${original.sequence}`, "消息已确认");
      await command(bots.requester, regular, "重复消息已识别");
      await dropConnection(bots.provider);
      await pause(500);
      bots.provider = await connect(mineflayer, options.host, Number(options.port), names.provider);
      assert.equal(bots.provider.player.uuid, provider);
      await command(bots.provider, "radio inbox", "Participant is not registered");
      await command(bots.provider, "radio join recovery 0 0 0 16", "已加入虚拟无线网络");
      await command(bots.provider, "radio inbox", "虚拟信箱 0 条");
      result.checks.push("original_envelope_ack_and_cross_tick_dedup_persist_through_hard_disconnect");

      await command(bots.requester, `radio send contract ${provider} 12 missing_order fulfill 1`, "消息已排入");
      await command(bots.provider, "radio inbox", "Contract message retained for review");
      saved = read(radioFile);
      state.invalidMessage = saved.radio.evidence.find(item => item.sender === requester && item.sequence === 12);
      assert.ok(state.invalidMessage);
      assert.equal(saved.contracts.received.length, 3);
      // The remaining active offer deliberately expires across the following restart.
      await command(bots.requester, `radio send contract ${provider} 13 expires_after_restart offer 1`, "消息已排入");
      await commandUntil(bots.provider, "radio inbox", "expires_after_restart · OFFERED");
      saved = read(radioFile);
      state.expiringContract = saved.contracts.states.find(item => item.id === "expires_after_restart");
      assert.equal(state.expiringContract.state, "OFFERED");
      result.checks.push("invalid_contract_message_stays_pending_and_separate_active_offer_is_saved_for_timeout");
    } else {
      assert.equal(input.phase, "verify");
      await command(bots.requester, "radio contract restart_order", "COMPLETED · units=3/3");
      await command(bots.provider, "radio inbox", "Contract message retained for review");
      let saved = read(radioFile);
      assert.deepEqual(saved.radio.pending.map(item => item.message), [state.invalidMessage]);
      await commandUntil(bots.requester, "radio contract expires_after_restart", "EXPIRED");
      await command(bots.requester, offer, "重复消息已识别");
      await command(bots.requester, regular, "重复消息已识别");
      const invalid = state.invalidMessage;
      await command(bots.provider, `radio ack ${invalid.sender} ${invalid.tick} ${invalid.sequence}`, "消息已确认");
      await command(bots.provider, "radio inbox", "虚拟信箱 0 条");
      saved = read(radioFile);
      assert.equal(saved.radio.evidence.length, 6);
      assert.equal(saved.radio.acknowledged.length, 6);
      assert.equal(saved.radio.events.length, 12);
      assert.equal(saved.radio.pending.length, 0);
      assert.equal(saved.contracts.received.length, 4);
      assert.equal(saved.contracts.events.length, 5);
      const expiration = saved.contracts.events.find(item => item.type === "contract.expire");
      assert.equal(expiration.tick, state.expiringContract.expiresAt + 1);
      assert.equal(expiration.contractId, state.expiringContract.id);
      assert.equal(saved.contracts.states.find(item => item.id === "restart_order").state, "COMPLETED");
      assert.equal(saved.contracts.states.find(item => item.id === state.expiringContract.id).state, "EXPIRED");
      result.checks.push("completed_contract_and_invalid_pending_envelope_restore_without_reapplication",
        "active_contract_times_out_once_and_manual_ack_discards_only_the_invalid_envelope");
    }
    fs.writeFileSync(stateFile, JSON.stringify(state, null, 2) + "\n", "utf8");
    const saved = read(radioFile);
    result.snapshotAtFinish = { tick: saved.radio.tick, messages: saved.radio.evidence.length,
      pending: saved.radio.pending.length, acknowledged: saved.radio.acknowledged.length,
      radioEvents: saved.radio.events.length, received: saved.contracts.received.length,
      contractEvents: saved.contracts.events.length, contracts: saved.contracts.states };
    result.passed = true;
  } catch (error) { result.error = error.stack || String(error); }
  finally {
    await Promise.all(Object.values(bots).map(disconnect));
    fs.writeFileSync(options.output, JSON.stringify(result, null, 2) + "\n", "utf8");
  }
  console.log(JSON.stringify(result, null, 2));
  process.exit(result.passed ? 0 : 1);
}

module.exports = { sameCommittedRadio };
if (require.main === module) main().catch(error => { console.error(error); process.exitCode = 1; });
