"use strict";

// Four fixed offline identities are deliberately reused across all three server processes.
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { pause, waitForMessage, connect, command, commandUntil, disconnect,
  organizationForPair, faultSeedForFactoryOrder } = require("./offline_client_workflow_smoke.cjs");

const names = { alice: "SGLifeAlice", bob: "SGLifeBob", carol: "SGLifeCarol", dave: "SGLifeDave" };
const storageDirectories = ["ecology", "factory", "arena-matches", "labs/lab"];
const hash = (data) => crypto.createHash("sha256").update(data).digest("hex");
const read = (file) => JSON.parse(fs.readFileSync(file, "utf8"));

function storageManifest(root) {
  const manifest = {};
  for (const directory of storageDirectories) {
    const folder = path.join(root, directory);
    if (!fs.existsSync(folder)) continue;
    for (const name of fs.readdirSync(folder).filter((name) => name.endsWith(".json")).sort()) {
      const relative = `${directory}/${name}`;
      manifest[relative] = hash(fs.readFileSync(path.join(root, relative)));
    }
  }
  return manifest;
}

function verifyStorage(root, expected) {
  assert.ok(Object.keys(expected).length >= 6, "Missing restart storage evidence");
  assert.deepEqual(storageManifest(root), expected, "Startup/join changed persisted evidence before any mutation");
}

async function dashboard(bot, prefix) {
  const from = bot.messages.length;
  await command(bot, `${prefix} dashboard`, "state=", 30000);
  return bot.messages.slice(from);
}

async function advanceEcology(bot, from, to) {
  for (let tick = from; tick < to;) {
    const steps = Math.min(20, to - tick);
    tick += steps;
    await command(bot, `ecology step ${steps}`, `生态舱 ${tick}/1536`, 30000);
    if (tick % 200 === 0 || tick === to) console.log(`ecology reached ${tick}/1536`);
  }
}

async function checkEcology(bot, data, owner, tick) {
  const lines = await dashboard(bot, "ecology");
  assert.ok(lines.some((line) => line.includes(`生态舱 ${tick}/1536`)));
  assert.ok(lines.some((line) => line.includes("Matter=1000")));
  const population = {};
  for (const species of ["producer", "grazer", "decomposer"]) {
    const line = lines.find((line) => line.startsWith(`${species} `));
    population[species] = Number(line?.match(new RegExp(`^${species} (\\d+)`))?.[1]);
    assert.ok(population[species] > 0, `Missing live ${species} at tick ${tick}: ${line}`);
  }
  const saved = read(path.join(data, "ecology", `${owner}.ecology.json`));
  assert.equal(saved.owner, owner);
  assert.equal(saved.replay.engineVersion, "genesis-ecology/2");
  assert.equal(saved.replay.frames.length, tick);
  assert.equal(saved.replay.config.maxTicks, 1536);
  const stateHash = saved.replay.frames.at(-1).stateHash;
  assert.ok(lines.some((line) => line.includes(`state=${stateHash.slice(0, 12)}`)));
  await command(bot, "ecology replay", `生态回放通过 ${tick} ticks`, 30000);
  return { tick, stateHash, population, matter: 1000, engineVersion: saved.replay.engineVersion, replayVerified: true };
}

async function stopWithActiveLab(bots, result, phase, kind, previousLab) {
  const alice = bots.alice;
  if (phase === "prepare") {
    await command(alice, "lab start 42 4", kind === "folia" ? "Folia 实验已启动" : "实验开始");
  } else {
    await command(alice, "lab replay", "回放开始");
    await commandUntil(alice, "lab status", "REPLAY_HASH_AND_EVENTS_VERIFIED");
    await command(alice, "lab resume", "正在受控线程读取并验证最近检查点");
  }
  await pause(1500);
  const status = await commandUntil(alice, "lab status", "试验箱 RUNNING");
  const tick = Number(status.match(/step=(\d+)/)?.[1]);
  assert.ok(tick > (previousLab?.tick || 0), `Lab did not advance beyond its previous checkpoint: ${status}`);
  result.labAtStop = { tick, status, menuOpen: Boolean(alice.currentWindow), priorTick: previousLab?.tick || 0,
    priorWorldId: previousLab?.worldId || null, priorReplayVerified: phase === "resume" };
  if (kind === "paper") assert.ok(alice.currentWindow, "Paper lab projection was not open at shutdown");
  const ended = Promise.all(Object.values(bots).map((bot) => new Promise((resolve) => bot.once("end", resolve))));
  console.log("READY_TO_STOP_WITH_LAB_ACTIVE");
  let timer;
  try {
    await Promise.race([ended, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error("Server did not disconnect the active lab clients")), 45000);
    })]);
  } finally { clearTimeout(timer); }
  result.serverDisconnectedAllClients = true;
}

async function main() {
  const options = {};
  for (let i = 2; i < process.argv.length; i += 2) options[process.argv[i].replace(/^--/, "")] = process.argv[i + 1];
  for (const key of ["host", "port", "kind", "module-root", "data-directory", "output", "phase"])
    assert.ok(options[key], `Missing --${key}`);
  assert.ok(["prepare", "resume", "verify"].includes(options.phase));
  const phase = options.phase;
  const data = options["data-directory"];
  const statePath = path.join(path.dirname(data), "lifecycle-client-state.json");
  const result = { passed: false, phase, kind: options.kind, names, checks: [] };
  const bots = {};
  try {
    const mineflayer = require(path.join(path.resolve(options["module-root"]), "mineflayer"));
    const expected = phase === "prepare" ? null : read(options.manifest);
    if (expected) verifyStorage(data, expected);
    for (const [role, name] of Object.entries(names)) {
      bots[role] = await connect(mineflayer, options.host, Number(options.port), name);
    }
    const identities = Object.fromEntries(Object.entries(bots).map(([role, bot]) => [role, bot.player.uuid]));
    result.identities = identities;
    const owner = identities.alice;
    let state;
    let previousLab;
    if (phase === "prepare") {
      assert.ok(!fs.existsSync(statePath), "Prepare requires a fresh isolated directory");
      state = { identities };
      await command(bots.alice, "ecology start 42 1536", "生态舱");
      await advanceEcology(bots.alice, 0, 768);
      await command(bots.alice, `factory invite ${names.bob}`, "已邀请");
      await command(bots.bob, "factory accept", "已组成双人模拟组织");
      await command(bots.carol, `factory invite ${names.dave}`, "已邀请");
      await command(bots.dave, "factory accept", "已组成双人模拟组织");
      const organization = organizationForPair(bots, "alice", "bob") < organizationForPair(bots, "carol", "dave")
        ? "org_cedar" : "org_ember";
      state.factoryFaultSeed = faultSeedForFactoryOrder(organization, "restart_fault", 5, 7);
      const factory = await command(bots.alice, `factory start ${state.factoryFaultSeed}`, "工厂 ");
      state.factoryId = factory.match(/match=([^\s]+)/)?.[1];
      assert.ok(state.factoryId);
      await command(bots.alice, "factory bid 15", "工厂 ");
      await command(bots.alice, "factory step 4", "工厂 ");
      await command(bots.alice, "factory order restart_fault 2", "restart_fault RUNNING");
      await command(bots.alice, `arena match invite ${names.bob} cedar`, "已邀请");
      await command(bots.bob, "arena match accept", "已加入队伍 cedar");
      await command(bots.carol, `arena match invite ${names.dave} moss`, "已邀请");
      await command(bots.dave, "arena match accept", "已加入队伍 moss");
      const arena = await command(bots.alice, "arena match start genesis:arena_ruleset 1.0.0 42", "matchId=");
      state.arenaId = arena.match(/matchId=([^\s]+)/)?.[1];
      assert.ok(state.arenaId);
      await command(bots.alice, "arena match pass", "动作已封存");
    } else {
      state = read(statePath);
      assert.deepEqual(identities, state.identities, "Player UUID changed across server restart");
      for (const bot of Object.values(bots)) {
        const factory = await waitForMessage(bot, 0, "已恢复工厂", 30000);
        const arena = await waitForMessage(bot, 0, "已恢复 Arena match", 30000);
        assert.ok(factory.includes(state.factoryId));
        assert.ok(arena.includes(state.arenaId));
        assert.ok(arena.includes(`pending=${phase === "resume" ? 1 : 0}`));
      }
      verifyStorage(data, expected);
      result.checks.push("all_saved_bytes_unchanged_after_startup_and_four_player_join", "four_player_identity_and_match_restore");
      previousLab = read(path.join(data, "labs/lab", `${owner}.lab.json`)).snapshot;
      result.ecologyBefore = await checkEcology(bots.alice, data, owner, phase === "resume" ? 768 : 1536);
    }
    if (phase === "resume") {
      await advanceEcology(bots.alice, 768, 1536);
      await command(bots.alice, "factory matches", state.factoryId);
      await command(bots.alice, `factory select ${state.factoryId}`, "工厂 ");
      await command(bots.alice, "factory leave", "比赛结束后才能退出");
      await command(bots.alice, `factory prune ${state.factoryId}`, "permission denied");
      const from = bots.alice.messages.length;
      await command(bots.alice, "factory step 2", "FAILED_RECOVERED");
      await waitForMessage(bots.alice, from, "recovered=1");
      assert.ok(bots.alice.messages.slice(from).some((line) => /credits=85 · ore=16 · parts=0/.test(line)),
        "Restarted factory did not refund inputs and release escrow");
      await command(bots.alice, `arena match select ${state.arenaId}`, "已选择 Arena match");
      await command(bots.alice, "arena match leave", "比赛结束后才能退出");
      await command(bots.alice, `arena match prune ${state.arenaId}`, "permission denied");
      await command(bots.alice, "arena match withdraw", "已撤回本轮");
      await command(bots.alice, "arena match matches", "pending=0");
      await command(bots.alice, "arena match pass", "动作已封存");
      await command(bots.bob, "arena match pass", "动作已封存");
      await command(bots.carol, "arena match pass", "动作已封存");
      await command(bots.dave, "arena match pass", "Territory=");
      result.checks.push("factory_pending_order_compensated_after_restart", "factory_select_leave_and_prune_guards",
        "arena_pending_action_withdrawn_and_round_completed", "arena_select_leave_and_prune_guards");
    }
    result.ecology = await checkEcology(bots.alice, data, owner, phase === "prepare" ? 768 : 1536);
    await command(bots.alice, "factory replay", "四人工厂回放通过", 30000);
    await command(bots.alice, "arena match replay", "Arena replay verified", 30000);
    result.factory = await dashboard(bots.alice, "factory");
    result.arena = await dashboard(bots.alice, "arena match");
    if (phase === "verify") {
      await command(bots.alice, "lab replay", "回放开始");
      await commandUntil(bots.alice, "lab status", "REPLAY_HASH_AND_EVENTS_VERIFIED");
      verifyStorage(data, expected);
      result.checks.push("read_only_replays_preserve_all_saved_bytes");
    } else {
      fs.writeFileSync(statePath, JSON.stringify(state, null, 2) + "\n");
      await stopWithActiveLab(bots, result, phase, options.kind, previousLab);
    }
    result.checks.push("ecology_v2_three_species_matter_and_replay", "factory_and_arena_replay");
    result.passed = true;
  } catch (error) {
    result.error = error.stack || String(error);
  } finally {
    await Promise.all(Object.values(bots).map(disconnect));
    fs.writeFileSync(options.output, JSON.stringify(result, null, 2) + "\n", "utf8");
  }
  console.log(JSON.stringify(result, null, 2));
  process.exit(result.passed ? 0 : 1);
}

module.exports = { storageManifest, verifyStorage };
if (require.main === module) main().catch((error) => { console.error(error); process.exitCode = 1; });
