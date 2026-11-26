"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { pause, waitForMessage, connect, command, disconnect, dropConnection,
  organizationForPair, faultSeedForFactoryOrder } = require("./offline_client_workflow_smoke.cjs");

const names = { alice: "SGMatchAlice", bob: "SGMatchBob", carol: "SGMatchCarol", dave: "SGMatchDave", admin: "SGReview" };
const roles = ["alice", "bob", "carol", "dave"];
const ruleset = "test:match_lifecycle";
const read = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const sha = (file) => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
const factoryPath = (data, id) => path.join(data, "factory", `${id}.factory.json`);
const arenaPath = (data, id) => path.join(data, "arena-matches", `${id}.match.json`);

function manifest(data) {
  const result = {};
  for (const directory of ["factory", "arena-matches", "arenas"]) {
    const folder = path.join(data, directory);
    if (!fs.existsSync(folder)) continue;
    for (const entry of fs.readdirSync(folder, { recursive: true, withFileTypes: true })) {
      if (!entry.isFile() || !entry.name.endsWith(".json")) continue;
      const file = path.join(entry.parentPath, entry.name);
      result[path.relative(data, file).replaceAll(path.sep, "/")] = sha(file);
    }
  }
  return Object.fromEntries(Object.entries(result).sort());
}

function verifyRestart(data, expected) {
  assert.ok(expected && Object.keys(expected).length >= 6, "Missing match lifecycle evidence");
  assert.deepEqual(manifest(data), expected, "Restart changed, removed or resurrected match evidence");
}

function assertReorganized(data, ids) {
  const pairs = read(path.join(data, "factory/organizations.json")).pairs;
  const actualPairs = pairs.map((pair) => [pair.first, pair.second].sort().join(":")).sort();
  const expectedPairs = [[ids.alice, ids.carol], [ids.bob, ids.dave]].map((pair) => pair.sort().join(":")).sort();
  assert.deepEqual(actualPairs, expectedPairs, "Organization roster did not retain the new pairs");
  assert.deepEqual(read(path.join(data, "arena-matches/teams.json")).teams,
    { [ids.alice]: "cedar", [ids.carol]: "cedar", [ids.bob]: "ember", [ids.dave]: "ember" });
}

async function dashboard(bot, prefix) {
  const from = bot.messages.length;
  await command(bot, `${prefix} dashboard`, "state=", 30000);
  return bot.messages.slice(from);
}

async function pair(bots, first, second, team) {
  await command(bots[first], `factory invite ${names[second]}`, "已邀请");
  await command(bots[second], "factory accept", "已组成双人模拟组织");
  await command(bots[first], `arena match invite ${names[second]} ${team}`, "已邀请");
  await command(bots[second], "arena match accept", `已加入队伍 ${team}`);
}

async function start(bots, seed) {
  const factory = await command(bots.alice, `factory start ${seed}`, "工厂 ");
  const arena = await command(bots.alice, `arena match start ${ruleset} 1.0.0 42`, "matchId=");
  const ids = { factory: factory.match(/match=([^\s]+)/)?.[1], arena: arena.match(/matchId=([^\s]+)/)?.[1] };
  assert.ok(ids.factory && ids.arena);
  return ids;
}

async function activePruneGuards(bots, data, match) {
  const before = manifest(data);
  await command(bots.admin, `factory prune ${match.factory}`, "只能清理已结束");
  await command(bots.admin, `arena match prune ${match.arena}`, "只能清理已结束");
  assert.deepEqual(manifest(data), before, "Rejected administrative prune changed storage");
}

async function main() {
  const options = {};
  for (let index = 2; index < process.argv.length; index += 2)
    options[process.argv[index].replace(/^--/, "")] = process.argv[index + 1];
  for (const key of ["host", "port", "kind", "module-root", "data-directory", "output"])
    assert.ok(options[key], `Missing --${key}`);
  const data = options["data-directory"], parent = path.dirname(data);
  const request = read(path.join(parent, "match-admin-input.json"));
  const phase = request.phase;
  assert.ok(["prepare", "reorganize", "verify"].includes(phase));
  const stateFile = path.join(parent, "match-admin-client-state.json");
  const state = phase === "prepare" ? {} : read(stateFile);
  const result = { passed: false, phase, kind: options.kind, players: 5, checks: [] };
  const bots = {};
  try {
    const mineflayer = require(path.join(path.resolve(options["module-root"]), "mineflayer"));
    if (phase !== "prepare") verifyRestart(data, request.manifest);
    for (const [role, name] of Object.entries(names)) {
      bots[role] = await connect(mineflayer, options.host, Number(options.port), name);
      await pause(700);
    }
    const ids = Object.fromEntries(Object.entries(bots).map(([role, bot]) => [role, bot._client.uuid]));
    result.identities = ids;
    if (phase === "prepare") state.identities = ids;
    else {
      assert.deepEqual(ids, state.identities);
      const expected = phase === "reorganize" ? state.old : state.active;
      for (const role of roles) {
        assert.ok((await waitForMessage(bots[role], 0, "已恢复工厂", 30000)).includes(expected.factory));
        assert.ok((await waitForMessage(bots[role], 0, "已恢复 Arena match", 30000)).includes(expected.arena));
      }
      verifyRestart(data, request.manifest);
      result.checks.push("restart_and_joins_preserve_all_match_and_design_bytes");
    }

    if (phase === "prepare") {
      await command(bots.alice, `factory invite ${names.bob}`, "已邀请");
      await command(bots.alice, `arena match invite ${names.bob} cedar`, "已邀请");
      await dropConnection(bots.alice);
      await pause(1400);
      await command(bots.bob, "factory accept", "没有可接受");
      await command(bots.bob, "arena match accept", "没有可接受");
      bots.alice = await connect(mineflayer, options.host, Number(options.port), names.alice);
      assert.equal(bots.alice._client.uuid, ids.alice);
      await pair(bots, "alice", "bob", "cedar");
      await pair(bots, "carol", "dave", "ember");
      await command(bots.alice, `arena create ${ruleset}`, "已创建私有");
      await command(bots.alice, "arena set width 4", "已修改 width");
      await command(bots.alice, "arena set height 4", "已修改 height");
      await command(bots.alice, "arena set duration 2", "已修改 duration");
      await command(bots.alice, "arena access public on", "已更新 public");
      await command(bots.alice, "arena save", "已原子保存");
      await command(bots.alice, "arena publish", "已提交审核");
      await command(bots.admin, `arena review approve ${ruleset} 1.0.0`, "已批准并发布");
      const organization = organizationForPair(bots, "alice", "bob") < organizationForPair(bots, "carol", "dave")
        ? "org_cedar" : "org_ember";
      state.seed = faultSeedForFactoryOrder(organization, "terminal_fault", 5, 7);
      state.old = await start(bots, state.seed);
      await activePruneGuards(bots, data, state.old);
      await command(bots.admin, `arena match watch ${state.old.arena}`, "read-only");
      await command(bots.alice, "factory bid 15", "工厂 ");
      await command(bots.alice, "factory step 4", "工厂 ");
      await command(bots.alice, "factory order terminal_fault 2", "terminal_fault RUNNING");
      await command(bots.alice, "factory step 2", "FAILED_RECOVERED");
      const refunded = (await dashboard(bots.alice, "factory")).find((line) => line.includes(organization));
      assert.ok(refunded?.includes("credits=85") && refunded.includes("ore=16") && refunded.includes("parts=0"));
      let tick = read(factoryPath(data, state.old.factory)).replay.frames.length;
      while (tick < 100) {
        const count = Math.min(10, 100 - tick);
        await command(bots.alice, `factory step ${count}`, `工厂 ${tick + count}/100`);
        tick += count;
      }
      for (let round = 0; round < 2; round++)
        for (const role of roles) await command(bots[role], "arena match pass", role === "dave" ? "Territory=" : "动作已封存");
      const before = manifest(data);
      await command(bots.alice, `factory prune ${state.old.factory}`, "permission denied");
      await command(bots.alice, `arena match prune ${state.old.arena}`, "permission denied");
      assert.deepEqual(manifest(data), before);
      result.checks.push("hard_disconnect_cancels_both_invitations", "new_connection_can_invite_and_join",
        "active_admin_prune_rejected_without_changes", "compensated_factory_and_short_arena_reach_terminal",
        "non_operator_cannot_prune_terminal_shared_matches");
    }

    if (phase === "reorganize") {
      const oldFactory = fs.readFileSync(factoryPath(data, state.old.factory));
      const oldArena = fs.readFileSync(arenaPath(data, state.old.arena));
      for (const role of roles) {
        await command(bots[role], "factory leave", role === "alice" || role === "carol" ? "已退出双人组织" : "当前没有双人组织");
        await command(bots[role], "arena match leave", "已退出赛前队伍");
      }
      await pair(bots, "alice", "carol", "cedar");
      await pair(bots, "bob", "dave", "ember");
      assertReorganized(data, ids);
      state.active = await start(bots, state.seed);
      assert.notEqual(state.active.factory, state.old.factory);
      assert.notEqual(state.active.arena, state.old.arena);
      assert.deepEqual(fs.readFileSync(factoryPath(data, state.old.factory)), oldFactory);
      assert.deepEqual(fs.readFileSync(arenaPath(data, state.old.arena)), oldArena);
      await command(bots.alice, "arena match pass", "动作已封存");
      await command(bots.alice, `factory select ${state.old.factory}`, "工厂 ");
      await command(bots.alice, `arena match select ${state.old.arena}`, "已选择 Arena match");
      const beforeReconnect = manifest(data);
      await dropConnection(bots.alice);
      await pause(1400);
      bots.alice = await connect(mineflayer, options.host, Number(options.port), names.alice);
      assert.equal(bots.alice._client.uuid, ids.alice);
      assert.ok((await waitForMessage(bots.alice, 0, "已恢复工厂", 30000)).includes(state.active.factory));
      const restored = await waitForMessage(bots.alice, 0, "已恢复 Arena match", 30000);
      assert.ok(restored.includes(state.active.arena) && restored.includes("pending=1"));
      assert.deepEqual(manifest(data), beforeReconnect);
      const activeFactory = sha(factoryPath(data, state.active.factory)), activeArena = sha(arenaPath(data, state.active.arena));
      const retired = path.join(parent, "match-admin-retired");
      fs.mkdirSync(retired);
      fs.writeFileSync(path.join(retired, "factory.json"), oldFactory);
      fs.writeFileSync(path.join(retired, "arena.json"), oldArena);
      await command(bots.admin, `factory prune ${state.old.factory}`, "已清理结束的工厂");
      await command(bots.admin, `arena match prune ${state.old.arena}`, "已清理完成的历史比赛");
      assert.ok(!fs.existsSync(factoryPath(data, state.old.factory)) && !fs.existsSync(arenaPath(data, state.old.arena)));
      assert.equal(sha(factoryPath(data, state.active.factory)), activeFactory);
      assert.equal(sha(arenaPath(data, state.active.arena)), activeArena);
      assertReorganized(data, ids);
      await command(bots.alice, `factory select ${state.old.factory}`, "比赛不存在");
      await command(bots.alice, `arena match select ${state.old.arena}`, "match 不存在");
      await command(bots.admin, "arena match dashboard", "不属于 Arena match");
      await activePruneGuards(bots, data, state.active);
      result.checks.push("terminal_members_leave_and_form_new_pairs_and_teams", "new_runs_preserve_old_history",
        "hard_reconnect_prefers_active_matches_and_retains_pending", "admin_prunes_only_terminal_history",
        "deleted_history_and_observer_selection_unavailable", "active_matches_and_new_rosters_unchanged_by_prune");
    }

    if (phase === "verify") {
      assertReorganized(data, ids);
      assert.ok(!fs.existsSync(factoryPath(data, state.old.factory)) && !fs.existsSync(arenaPath(data, state.old.arena)));
      const active = read(arenaPath(data, state.active.arena));
      assert.deepEqual(Object.keys(active.pending), [ids.alice]);
      assert.equal(active.pending[ids.alice].actor, ids.alice);
      await activePruneGuards(bots, data, state.active);
      verifyRestart(data, request.manifest);
      result.checks.push("pruned_history_stays_absent_after_restart", "new_rosters_active_matches_and_pending_restore");
    }

    await command(bots.alice, "factory replay", "四人工厂回放通过", 30000);
    await command(bots.alice, "arena match replay", "Arena replay verified", 30000);
    result.factory = await dashboard(bots.alice, "factory");
    result.arena = await dashboard(bots.alice, "arena match");
    result.state = state;
    result.manifest = manifest(data);
    fs.writeFileSync(stateFile, JSON.stringify(state, null, 2) + "\n");
    result.passed = true;
  } catch (failure) { result.error = failure.stack || String(failure); }
  finally {
    await Promise.all(Object.values(bots).map(disconnect));
    fs.writeFileSync(options.output, JSON.stringify(result, null, 2) + "\n");
  }
  console.log(JSON.stringify(result, null, 2));
  process.exit(result.passed ? 0 : 1);
}

module.exports = { manifest, verifyRestart, assertReorganized };
if (require.main === module) main().catch((failure) => { console.error(failure); process.exitCode = 1; });
