"use strict";
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const { connect, disconnect, dropConnection, command, commandUntil, waitForMessage, pause } = require("./offline_client_workflow_smoke.cjs");
async function main() {
  const options = {};
  for (let i = 2; i < process.argv.length; i += 2) options[process.argv[i].replace(/^--/, "")] = process.argv[i + 1];
  const result = { passed: false, checks: [], humanAcceptance: false };
  const mineflayer = require(path.join(path.resolve(options["module-root"]), "mineflayer"));
  const bots = [];
  async function projection(bot, value, expected) {
    const from = bot.messages.length;
    await pause(Math.max(0, (bot.lastCommandAt || 0) + 1200 - Date.now()));
    bot.lastCommandAt = Date.now();
    bot.chat("/genesisprojection " + value);
    return waitForMessage(bot, from, expected);
  }
  async function observed(predicate) {
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      try {
        const value = JSON.parse(fs.readFileSync(options.observed, "utf8"));
        if (predicate(value)) return value;
      } catch (ignored) {}
      await pause(150);
    }
    throw new Error("Timed out waiting for server projection observation");
  }
  try {
    const denied = await connect(mineflayer, options.host, Number(options.port), "SGProjectBob");
    bots.push(denied);
    await projection(denied, "start stargenesis/catalog_probe", "Unknown or incomplete command");
    await disconnect(denied);
    result.checks.push("ordinary_player_cannot_start_projection");
    let alice = await connect(mineflayer, options.host, Number(options.port), "SGProjectAlice");
    bots.push(alice);
    await pause(600);
    await projection(alice, "start stargenesis/catalog_probe", "先启动自己的实验室");
    await command(alice, "lab start 42 2 commands", "命令模式实验已启动");
    await commandUntil(alice, "lab status", "RUNNING");
    assert.equal(alice.currentWindow, null, "Command mode opened an inventory");
    await projection(alice, "start stargenesis/catalog_probe", "模型预览已开启");
    await projection(alice, "status", "instances=2");
    await observed(value => value.matched > 5 && value.lastTick > value.firstTick);
    result.checks.push("real_lab_worker_drives_passive_models_without_inventory");
    await command(alice, "lab stop", "安全停止");
    await commandUntil(alice, "lab status", "STOPPED");
    await projection(alice, "status", "当前没有模型预览");
    await observed(value => value.instances === 0 && value.entities === 0 && value.clean > 5);
    await command(alice, "lab resume commands", "正在受控线程读取并验证");
    await commandUntil(alice, "lab status", "RUNNING");
    await projection(alice, "start stargenesis/catalog_probe", "模型预览已开启");
    await observed(value => value.sessions >= 2 && value.instances === 2);
    result.checks.push("lab_stop_cleans_entities_and_resume_uses_new_projection_session");
    const uuid = alice.player.uuid;
    await dropConnection(alice);
    await observed(value => value.instances === 0 && value.entities === 0);
    alice = await connect(mineflayer, options.host, Number(options.port), "SGProjectAlice");
    bots.push(alice);
    assert.equal(alice.player.uuid, uuid);
    await projection(alice, "status", "当前没有模型预览");
    result.checks.push("hard_disconnect_cleans_entities_and_reconnect_has_no_stale_view");
    result.server = await observed(value => value.instances === 0 && value.entities === 0);
    result.passed = true;
  } catch (error) { result.error = error.stack || String(error); }
  finally {
    for (const bot of bots) await disconnect(bot);
    fs.writeFileSync(options.output, JSON.stringify(result, null, 2) + "\n");
  }
  console.log(JSON.stringify(result));
  process.exit(result.passed ? 0 : 1);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
