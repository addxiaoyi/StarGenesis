"use strict";
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const { pause, connect, command, disconnect, dropConnection } = require("./offline_client_workflow_smoke.cjs");
const sha = (value) => crypto.createHash("sha256").update(value).digest("hex");

async function main() {
  const options = {};
  for (let i = 2; i < process.argv.length; i += 2) options[process.argv[i].replace(/^--/, "")] = process.argv[i + 1];
  const names = { author: "SGDraftAuthor", editor: "SGDraftEditor", reviewer: "SGReview" };
  const bots = {};
  const result = { passed: false, kind: options.kind, players: 3, humanAcceptance: false, checks: [] };
  try {
    const mineflayer = require(path.join(path.resolve(options["module-root"]), "mineflayer"));
    const reconnect = async (role) => {
      const uuid = bots[role].player.uuid;
      await dropConnection(bots[role]);
      await pause(800);
      bots[role] = await connect(mineflayer, options.host, Number(options.port), names[role]);
      assert.equal(bots[role].player.uuid, uuid);
    };
    for (const [role, username] of Object.entries(names))
      bots[role] = await connect(mineflayer, options.host, Number(options.port), username);
    result.identities = Object.fromEntries(Object.entries(bots).map(([role, bot]) => [role, bot.player.uuid]));
    const author = result.identities.author, editor = result.identities.editor;
    const id = "smoke:draft-lifecycle";
    const data = options["data-directory"];
    const file = path.join(data, "arenas", "drafts", author, `${sha(id)}.json`);
    await command(bots.author, `arena create ${id}`, "已创建私有 ArenaRuleset 草稿");
    await command(bots.author, `arena access editor ${editor}`, "已更新草稿编辑者许可");
    await command(bots.author, "arena access public on", "已更新 public 许可");
    await command(bots.author, "arena set duration 20", "已修改 duration");
    await command(bots.author, "arena save", "草稿已原子保存");
    await command(bots.editor, `arena open ${id}`, "已打开");
    await command(bots.editor, "arena set width 20", "已修改 width");
    await command(bots.author, "arena set width 28", "已修改 width");
    await command(bots.author, "arena save", "草稿已原子保存");
    const committed = fs.readFileSync(file);
    await command(bots.editor, "arena save", "Arena draft changed since it was opened");
    assert.deepEqual(fs.readFileSync(file), committed);
    await command(bots.editor, "arena preview", "Bounds=20x16");
    await command(bots.editor, "arena close", "草稿尚未保存");
    result.checks.push("conflicting_save_preserves_author_bytes_and_editor_unsaved_work");
    await reconnect("editor");
    await command(bots.editor, `arena open ${id}`, "已打开");
    await command(bots.editor, "arena preview", "Bounds=28x16");
    assert.deepEqual(fs.readFileSync(file), committed);
    result.checks.push("hard_disconnect_releases_workspace_and_rejoin_loads_saved_document");
    await command(bots.editor, "arena set width 24", "已修改 width");
    await command(bots.editor, "arena save", "草稿已原子保存");
    await command(bots.author, "arena close", "已关闭 ArenaRuleset 草稿");
    await command(bots.author, `arena open ${id}`, "已打开");
    await command(bots.author, "arena preview", "Bounds=24x16");
    await command(bots.author, `arena access revoke ${editor}`, "已更新草稿编辑者许可");
    await command(bots.author, "arena save", "草稿已原子保存");
    const revoked = fs.readFileSync(file);
    await command(bots.editor, "arena set width 32", "已修改 width");
    await command(bots.editor, "arena save", "Arena draft changed since it was opened");
    assert.deepEqual(fs.readFileSync(file), revoked);
    await reconnect("editor");
    await command(bots.editor, `arena open ${id}`, "Arena draft is not editable by this player");
    await command(bots.editor, "arena create smoke:editor-own", "已创建私有 ArenaRuleset 草稿");
    result.checks.push("revoked_editor_cannot_save_or_reopen_but_has_a_free_workspace_slot");
    await command(bots.author, "arena set width 40", "已修改 width");
    await reconnect("author");
    await command(bots.author, `arena open ${id}`, "已打开");
    await command(bots.author, "arena preview", "Bounds=24x16");
    assert.deepEqual(fs.readFileSync(file), revoked);
    await command(bots.author, "arena publish", "ArenaRuleset 已提交审核");
    await command(bots.reviewer, `arena review approve ${id} 1.0.0`, "已批准并发布");
    await command(bots.author, `arena review status ${id} 1.0.0`, "status=APPROVED");
    result.checks.push("author_rejoin_discards_only_unsaved_changes_and_publishes_saved_hash");
    await command(bots.author, "arena close", "已关闭 ArenaRuleset 草稿");
    await command(bots.author, "arena clone genesis:arena_ruleset smoke:draft-copy", "已克隆可复用规则");
    await command(bots.author, "arena close", "已关闭 ArenaRuleset 草稿");
    await command(bots.author, "arena open smoke:draft-copy", "已打开");
    result.checks.push("cloned_draft_reopens_after_explicit_close");
    const saved = JSON.parse(revoked.toString("utf8"));
    result.finalDraftFileSha256 = sha(revoked);
    result.finalDraftDesignHash = saved.metadata.hash;
    result.passed = true;
  } catch (error) { result.error = error.stack || String(error); }
  finally {
    await Promise.all(Object.values(bots).map(disconnect));
    fs.writeFileSync(options.output, JSON.stringify(result, null, 2) + "\n", "utf8");
  }
  console.log(JSON.stringify(result, null, 2));
  process.exit(result.passed ? 0 : 1);
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
