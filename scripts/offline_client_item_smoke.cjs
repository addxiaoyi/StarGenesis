"use strict";
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const { connect, disconnect, command, pause } = require("./offline_client_workflow_smoke.cjs");
async function main() {
  const options = {};
  for (let i = 2; i < process.argv.length; i += 2) options[process.argv[i].replace(/^--/, "")] = process.argv[i + 1];
  const mineflayer = require(path.join(path.resolve(options["module-root"]), "mineflayer"));
  const bots = [], result = { passed: false, checks: [], humanAcceptance: false };
  async function observed() {
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      try {
        const value = JSON.parse(fs.readFileSync(options.observed, "utf8"));
        if (value.passed) return value;
      } catch (_) {}
      await pause(150);
    }
    throw new Error("Server did not verify both full inventories");
  }
  try {
    const member = await connect(mineflayer, options.host, Number(options.port), "SGItemMember"); bots.push(member);
    const admin = await connect(mineflayer, options.host, Number(options.port), "SGItemAdmin"); bots.push(admin);
    const before = await observed();
    await command(member, "item held", "permission denied");
    await command(member, "item status", "permission denied");
    await command(member, "item inspect stargenesis:item_probe", "permission denied");
    result.checks.push("ordinary_player_denied_held_status_and_definition_queries");
    await command(admin, "item status", "loaded=1");
    await command(admin, "item inspect stargenesis:item_probe", "material=minecraft:paper");
    await command(admin, "item inspect stargenesis:missing", "物品尚未加载");
    await command(admin, "item held", "amount=3");
    await command(admin, "item held", "stargenesis:item_probe");
    await command(admin, "item create stargenesis:item_probe", "/genesis item status");
    result.checks.push("operator_can_inspect_held_state_and_missing_ids_without_grant_command");
    const after = await observed();
    assert.deepEqual(after.players, before.players);
    assert.ok(after.verifiedTicks > before.verifiedTicks);
    for (const bot of bots) {
      assert.equal(bot.heldItem.name, "paper"); assert.equal(bot.heldItem.count, 3);
      assert.equal(bot.inventory.items().length, 1);
    }
    result.checks.push("both_clients_keep_one_stack_of_three_and_server_verifies_complete_nbt_in_all_slots");
    result.observed = after; result.passed = true;
  } catch (error) { result.error = error.stack || String(error); }
  finally {
    for (const bot of bots) await disconnect(bot);
    fs.writeFileSync(options.output, JSON.stringify(result, null, 2) + "\n");
  }
  console.log(JSON.stringify(result)); process.exit(result.passed ? 0 : 1);
}
main().catch(error => { console.error(error); process.exitCode = 1; });
