package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetEditorSession;
import dev.stargenesis.design.ArenaRulesetValidator;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.sim.ArenaMatchWorld;
import dev.stargenesis.design.ArenaRuleset.Cell;
import dev.stargenesis.design.ArenaRuleset.Combination;
import dev.stargenesis.design.ArenaRuleset.Metric;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class ArenaRulesetMenu implements Listener {
    private static final int SIZE = 27;
    private static final int PUBLIC_READ = 2;
    private static final int ALLOW_CLONE = 5;
    private static final int WIDTH_DOWN = 10;
    private static final int WIDTH_UP = 11;
    private static final int HEIGHT_DOWN = 12;
    private static final int HEIGHT_UP = 13;
    private static final int DURATION_DOWN = 14;
    private static final int DURATION_UP = 15;
    private static final int UNITS_DOWN = 16;
    private static final int UNITS_UP = 17;
    private static final int PREVIEW = 19;
    private static final int UNDO = 20;
    private static final int VERSION = 21;
    private static final int SAVE = 22;
    private static final int PUBLISH = 23;
    private static final int CLOSE = 25;
    private static final int UPGRADE = 24;
    private static final int MAP = 26;
    private static final int MAP_BACK = 41;
    private static final int MAP_CLOSE = 44;
    private static final int MAP_PREVIEW = 36;
    private static final int MAP_UNDO = 37;
    private static final int MAP_SAVE = 38;
    private static final int MAP_PUBLISH = 39;
    private static final int MODE_PREVIOUS = 24;
    private static final int MODE_NEXT = 26;
    private static final int X_PREVIOUS = 0;
    private static final int X_NEXT = 8;
    private static final int Y_PREVIOUS = 1;
    private static final int Y_NEXT = 7;
    private static final int[] MAP_SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23, 28, 29, 30, 31, 32};
    private static final String[] MAP_MODES = {
            "障碍", "目标", "红队出生位 1", "红队出生位 2", "蓝队出生位 1", "蓝队出生位 2"
    };

    private final JavaPlugin plugin;
    private final DesignCodec codec = new DesignCodec();
    private final PublishedDesignStore releases;
    private final ArenaReviewQueue reviews;
    private final ArenaMatchService matches;
    private final NamespacedKey checkpointKey;
    private final NamespacedKey savedRulesetKey;
    private final ArenaRuleset starter;
    private final ArenaRuleset legacyStarter;

    ArenaRulesetMenu(JavaPlugin plugin, PublishedDesignStore releases, ArenaReviewQueue reviews,
                     ArenaMatchService matches) {
        this.plugin = plugin;
        this.releases = releases;
        this.reviews = reviews;
        this.matches = matches;
        checkpointKey = new NamespacedKey(plugin, "arena-editor-checkpoint-v1");
        savedRulesetKey = new NamespacedKey(plugin, "arena-ruleset-saved-v1");
        starter = loadStarter();
        legacyStarter = loadLegacyStarter();
    }

    void seedBundledRelease() throws IOException {
        releases.seedBundled(legacyStarter);
        releases.seedBundled(starter);
    }

    void open(Player player, boolean reset) {
        if (!player.hasPermission("stargenesis.arena")) {
            player.sendMessage(Component.text("你没有编辑竞技规则集的权限。"));
            return;
        }
        try {
            ArenaRulesetEditorSession session = loadEditor(player, reset);
            ArenaHolder holder = new ArenaHolder(player.getUniqueId(), session);
            Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("竞技规则集编辑器"));
            holder.inventory = inventory;
            inventory.setContents(render(holder));
            player.openInventory(inventory);
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException failure) {
            player.sendMessage(Component.text("无法打开竞技规则集编辑器：" + failure.getMessage()));
        }
    }

    private void openMap(Player player, ArenaHolder holder) {
        holder.mapMode = true;
        holder.viewX = holder.session.draft().width() / 2;
        holder.viewY = holder.session.draft().height() / 2;
        Inventory inventory = Bukkit.createInventory(holder, 45, Component.text("竞技地图编辑器"));
        holder.inventory = inventory;
        inventory.setContents(render(holder));
        player.openInventory(inventory);
    }

    void openMap(Player player) {
        if (!player.hasPermission("stargenesis.arena")) {
            player.sendMessage(Component.text("你没有编辑竞技规则集的权限。"));
            return;
        }
        try {
            ArenaRulesetEditorSession session = loadEditor(player, false);
            if (session.draft().layout() == null)
                throw new IllegalStateException("请先升级竞技规则集格式到 schema 2");
            openMap(player, new ArenaHolder(player.getUniqueId(), session));
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException failure) {
            player.sendMessage(Component.text("无法打开竞技地图编辑器：" + failure.getMessage()));
        }
    }

    void publishSaved(Player player) {
        try {
            ArenaRuleset ruleset = loadSaved(player);
            if (ruleset == null) {
                player.sendMessage(Component.text("请先在 /genesis arena 中预览并保存规则集。"));
                return;
            }
            submitForReview(player, ruleset);
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException failure) {
            player.sendMessage(Component.text("竞技规则集提交失败：" + failure.getMessage()));
        }
    }

    void listReviews(Player player) {
        try {
            List<ArenaReviewQueue.SubmissionView> pending = reviews.listPending();
            if (pending.isEmpty()) {
                player.sendMessage(Component.text("没有待审核的竞技规则集。"));
                return;
            }
            player.sendMessage(Component.text("待审核竞技规则集 " + pending.size()));
            pending.forEach(item -> player.sendMessage(Component.text(item.hash() + " | " + item.designId()
                    + "@" + item.version() + " | author=" + item.author() + " | submitter=" + item.submitter()
                    + (item.keyFingerprint().isEmpty() ? " | unsigned" : " | Ed25519=" + item.keyFingerprint()))));
        } catch (IOException failure) {
            player.sendMessage(Component.text("无法读取竞技审核队列：" + failure.getMessage()));
        }
    }

    void approveReview(Player player, String hash, String signature) {
        try {
            ArenaRuleset published = reviews.approve(hash, player.getUniqueId(), signature);
            player.sendMessage(Component.text("已审核并发布 " + published.metadata().id().value() + "@"
                    + published.metadata().version() + " #" + published.metadata().hash()));
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException failure) {
            player.sendMessage(Component.text("竞技规则集审核未通过：" + failure.getMessage()));
        }
    }

    void approveLegacyReviewRecovery(Player player, String hash) {
        try {
            ArenaRuleset published = reviews.approve(hash, player.getUniqueId());
            player.sendMessage(Component.text("已恢复旧版审核发布 " + published.metadata().id().value() + "@"
                    + published.metadata().version() + " #" + published.metadata().hash()));
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException failure) {
            player.sendMessage(Component.text("旧版竞技审核恢复未通过：" + failure.getMessage()));
        }
    }

    void rejectReview(Player player, String hash, String reason, String signature) {
        try {
            reviews.reject(hash, player.getUniqueId(), reason, signature);
            player.sendMessage(Component.text("已拒绝该规则集，原因已记录。"));
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            player.sendMessage(Component.text("竞技规则集审核未完成：" + failure.getMessage()));
        }
    }

    void rejectLegacyReviewRecovery(Player player, String hash, String reason) {
        try {
            reviews.reject(hash, player.getUniqueId(), reason);
            player.sendMessage(Component.text("已确认旧版拒绝记录。"));
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            player.sendMessage(Component.text("旧版竞技审核恢复未完成：" + failure.getMessage()));
        }
    }

    void changeEditor(Player player, UUID editorId, boolean grant) {
        try {
            ArenaRuleset saved = loadSaved(player);
            if (saved == null) throw new IllegalStateException("请先保存你的竞技规则集草稿");
            ArenaRulesetEditorSession session = ArenaRulesetEditorSession.edit(player.getUniqueId(), saved);
            if (grant) session.grantEditor(editorId);
            else session.revokeEditor(editorId);
            session.preview();
            String encoded = session.save(codec);
            player.getPersistentDataContainer().set(savedRulesetKey, PersistentDataType.BYTE_ARRAY,
                    encoded.getBytes(StandardCharsets.UTF_8));
            persistCheckpoint(player, session);
            player.sendMessage(Component.text("规则集草稿已" + (grant ? "授权" : "撤销") + "编辑者 " + editorId
                    + "，新版本 " + session.saved().metadata().version() + "。使用 /genesis arena publish 激活权限。"));
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException failure) {
            player.sendMessage(Component.text("编辑者权限未更改：" + failure.getMessage()));
        }
    }

    void listReleases(Player player) {
        try {
            List<ArenaRuleset> arenas = releases.list(DesignKind.ARENA_RULESET).stream()
                    .map(ArenaRuleset.class::cast)
                    .sorted(Comparator.comparing((ArenaRuleset ruleset) -> ruleset.metadata().id().value())
                            .thenComparing(ruleset -> ruleset.metadata().version()))
                    .toList();
            player.sendMessage(Component.text("已发布竞技规则集 " + arenas.size()));
            arenas.stream().limit(20).forEach(ruleset -> player.sendMessage(Component.text(
                    ruleset.metadata().id().value() + "@" + ruleset.metadata().version() + " #"
                            + ruleset.metadata().hash().substring(0, 12))));
            if (arenas.size() > 20) player.sendMessage(Component.text("仅显示前 20 个版本。"));
        } catch (IOException failure) {
            player.sendMessage(Component.text("无法读取竞技规则集发布目录：" + failure.getMessage()));
        }
    }

    void startMatch(Player player) {
        try {
            ArenaRuleset ruleset = loadSaved(player);
            if (ruleset == null) throw new IllegalStateException("请先保存 schema 2 竞技规则集");
            if (ruleset.layout() == null) throw new IllegalStateException("请先把规则集升级到 schema 2 并保存地图");
            matches.createRoom(player.getUniqueId(), ArenaRoom.reference(ruleset), reply(player));
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException failure) {
            player.sendMessage(Component.text("无法开始竞技对局：" + failure.getMessage()));
        }
    }

    void startMatch(Player player, String designId, String version) {
        try {
            DesignId requestedId = new DesignId(designId);
            ArenaRuleset ruleset = releases.list(DesignKind.ARENA_RULESET).stream()
                    .map(ArenaRuleset.class::cast)
                    .filter(candidate -> candidate.metadata().id().equals(requestedId)
                            && candidate.metadata().version().equals(version))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到指定的已发布规则版本"));
            DesignDependency reference = ArenaRoom.reference(ruleset);
            matches.createRoom(player.getUniqueId(), reference, reply(player));
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            player.sendMessage(Component.text("无法开始竞技对局：" + failure.getMessage()));
        }
    }

    void listMatches(Player player, int page) { matches.listRooms(page, reply(player)); }

    void joinMatch(Player player, UUID roomId, String slot) {
        matches.join(roomId, player.getUniqueId(), slot, reply(player));
    }

    void leaveMatch(Player player, UUID roomId) { matches.leave(roomId, player.getUniqueId(), reply(player)); }

    void startMatch(Player player, UUID roomId) { matches.startRoom(roomId, player.getUniqueId(), reply(player)); }

    void actMatch(Player player, UUID roomId, long tick, ArenaMatchWorld.Action action) {
        matches.act(roomId, player.getUniqueId(), tick, action, reply(player));
    }

    void matchStatus(Player player, UUID roomId) { matches.status(roomId, reply(player)); }

    void spectateMatch(Player player, UUID roomId) { matches.spectate(roomId, reply(player)); }

    void stopMatch(Player player, UUID roomId) { matches.stop(roomId, player.getUniqueId(), reply(player)); }

    void kickMatch(Player player, UUID roomId, UUID target) {
        matches.kick(roomId, player.getUniqueId(), target, reply(player));
    }

    private java.util.function.Consumer<String> reply(Player player) {
        return message -> player.getScheduler().run(plugin,
                task -> player.sendMessage(Component.text(message)), () -> { });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ArenaHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.arena")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.inventory.getSize()) return;
        try {
            if (holder.mapMode) activateMap(player, holder, slot);
            else activate(player, holder, slot);
            persistCheckpoint(player, holder.session);
            if (player.getOpenInventory().getTopInventory().getHolder() == holder)
                holder.inventory.setContents(render(holder));
        } catch (IOException | IllegalArgumentException | IllegalStateException | SecurityException failure) {
            player.sendMessage(Component.text("竞技规则集操作未应用：" + failure.getMessage()));
            holder.inventory.setContents(render(holder));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ArenaHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ArenaHolder holder
                && event.getPlayer() instanceof Player player && holder.playerId.equals(player.getUniqueId())) {
            try {
                persistCheckpoint(player, holder.session);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                plugin.getLogger().warning("Could not persist arena editor for " + player.getUniqueId()
                        + ": " + failure.getMessage());
            }
        }
    }

    private ArenaRulesetEditorSession loadEditor(Player player, boolean reset) throws IOException {
        if (reset) {
            ArenaRuleset previous = loadSaved(player);
            ArenaRulesetEditorSession created = ArenaRulesetEditorSession.create(player.getUniqueId(), starter, previous);
            persistCheckpoint(player, created);
            return created;
        }
        byte[] checkpoint = player.getPersistentDataContainer().get(checkpointKey, PersistentDataType.BYTE_ARRAY);
        if (checkpoint != null) {
            try {
                ArenaRulesetEditorSession restored = ArenaRulesetEditorSession.resume(player.getUniqueId(), checkpoint, codec);
                releases.requireCanEdit(restored.saved(), player.getUniqueId());
                return restored;
            } catch (IllegalArgumentException | SecurityException failure) {
                throw new IllegalArgumentException("规则集编辑存档无效，请使用 /genesis arena reset 重建。", failure);
            }
        }
        ArenaRuleset saved = loadSaved(player);
        ArenaRulesetEditorSession session = saved == null
                ? ArenaRulesetEditorSession.create(player.getUniqueId(), starter)
                : ArenaRulesetEditorSession.edit(player.getUniqueId(), saved);
        persistCheckpoint(player, session);
        return session;
    }

    private ArenaRuleset loadSaved(Player player) throws IOException {
        byte[] stored = player.getPersistentDataContainer().get(savedRulesetKey, PersistentDataType.BYTE_ARRAY);
        if (stored == null) return null;
        String json = new String(stored, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(stored, json.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Saved arena ruleset contains invalid UTF-8");
        DesignDocument document = codec.load(json);
        if (!(document instanceof ArenaRuleset ruleset))
            throw new IllegalArgumentException("Saved design is not an arena ruleset");
        if (!ruleset.metadata().permissions().mayEdit(player.getUniqueId(), ruleset.metadata().author()))
            throw new SecurityException("Saved arena ruleset is not editable by this player");
        releases.requireCanEdit(ruleset, player.getUniqueId());
        return ruleset;
    }

    private void activate(Player player, ArenaHolder holder, int slot) throws IOException {
        if (slot == WIDTH_DOWN) holder.session.adjustWidth(-1);
        else if (slot == WIDTH_UP) holder.session.adjustWidth(1);
        else if (slot == HEIGHT_DOWN) holder.session.adjustHeight(-1);
        else if (slot == HEIGHT_UP) holder.session.adjustHeight(1);
        else if (slot == DURATION_DOWN) holder.session.adjustDuration(-100);
        else if (slot == DURATION_UP) holder.session.adjustDuration(100);
        else if (slot == UNITS_DOWN) holder.session.adjustUnitBudget(-1);
        else if (slot == UNITS_UP) holder.session.adjustUnitBudget(1);
        else if ((slot == PUBLIC_READ || slot == ALLOW_CLONE)
                && holder.session.draft().metadata().author().equals(holder.playerId)) {
            var permissions = holder.session.draft().metadata().permissions();
            holder.session.setSharing(slot == PUBLIC_READ ? !permissions.publicRead() : permissions.publicRead(),
                    slot == ALLOW_CLONE ? !permissions.allowClone() : permissions.allowClone());
        }
        else if (slot == PREVIEW) showPreview(player, holder.session.preview());
        else if (slot == UNDO) {
            if (!holder.session.undo()) player.sendMessage(Component.text("没有可撤销的规则集编辑。"));
        } else if (slot == VERSION) {
            holder.session.bumpPatchVersion();
        } else if (slot == UPGRADE) {
            holder.session.upgrade(codec);
        } else if (slot == MAP && holder.session.draft().layout() != null) {
            openMap(player, holder);
        } else if (slot == 6 && holder.session.draft().victory() != null) {
            Combination current = holder.session.draft().victory().combination();
            holder.session.setVictoryMode(current == Combination.ANY ? Combination.ALL : Combination.ANY);
        } else if (slot == 7 && holder.session.draft().victory() != null) {
            adjustVictory(holder.session, Metric.CONTROLLED_OBJECTIVES, -1);
        } else if (slot == 8 && holder.session.draft().victory() != null) {
            adjustVictory(holder.session, Metric.CONTROLLED_OBJECTIVES, 1);
        } else if (slot == 9 && holder.session.draft().victory() != null) {
            adjustVictory(holder.session, Metric.OBJECTIVE_POINTS, -25);
        } else if (slot == 18 && holder.session.draft().victory() != null) {
            adjustVictory(holder.session, Metric.OBJECTIVE_POINTS, 25);
        } else if (slot == SAVE) {
            saveLocal(player, holder.session);
        } else if (slot == PUBLISH) {
            ArenaRuleset ruleset = saveLocal(player, holder.session);
            submitForReview(player, ruleset);
        } else if (slot == CLOSE) {
            player.closeInventory();
        }
    }

    private void activateMap(Player player, ArenaHolder holder, int slot) throws IOException {
        if (slot == MAP_BACK) {
            holder.mapMode = false;
            Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("竞技规则集编辑器"));
            holder.inventory = inventory;
            inventory.setContents(render(holder));
            player.openInventory(inventory);
            return;
        }
        if (slot == MAP_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == X_PREVIOUS) holder.viewX = Math.max(0, holder.viewX - 1);
        else if (slot == X_NEXT) holder.viewX = Math.min(holder.session.draft().width() - 1, holder.viewX + 1);
        else if (slot == Y_PREVIOUS) holder.viewY = Math.max(0, holder.viewY - 1);
        else if (slot == Y_NEXT) holder.viewY = Math.min(holder.session.draft().height() - 1, holder.viewY + 1);
        else if (slot == MODE_PREVIOUS) holder.mode = Math.floorMod(holder.mode - 1, MAP_MODES.length);
        else if (slot == MODE_NEXT) holder.mode = (holder.mode + 1) % MAP_MODES.length;
        else if (slot == MAP_PREVIEW) showPreview(player, holder.session.preview());
        else if (slot == MAP_UNDO) {
            if (!holder.session.undo()) player.sendMessage(Component.text("没有可撤销的规则集编辑。"));
        } else if (slot == MAP_SAVE) {
            saveLocal(player, holder.session);
        } else if (slot == MAP_PUBLISH) {
            ArenaRuleset ruleset = saveLocal(player, holder.session);
            submitForReview(player, ruleset);
        } else {
            int cellSlot = -1;
            for (int index = 0; index < MAP_SLOTS.length; index++) {
                if (MAP_SLOTS[index] == slot) {
                    cellSlot = index;
                    break;
                }
            }
            if (cellSlot < 0) return;
            int x = holder.viewX + cellSlot % 5 - 2;
            int y = holder.viewY + cellSlot / 5 - 1;
            if (x < 0 || y < 0 || x >= holder.session.draft().width() || y >= holder.session.draft().height()) return;
            Cell cell = new Cell(x, y);
            switch (holder.mode) {
                case 0 -> holder.session.toggleObstacle(cell);
                case 1 -> holder.session.toggleObjective(cell);
                case 2 -> holder.session.putSpawn("red", 1, cell);
                case 3 -> holder.session.putSpawn("red", 2, cell);
                case 4 -> holder.session.putSpawn("blue", 1, cell);
                case 5 -> holder.session.putSpawn("blue", 2, cell);
                default -> throw new IllegalStateException("Unknown arena map mode");
            }
        }
    }

    private static void adjustVictory(ArenaRulesetEditorSession session, Metric metric, int delta) {
        ArenaRuleset ruleset = session.draft();
        int current = ruleset.victory().conditions().stream().filter(condition -> condition.metric() == metric)
                .mapToInt(condition -> condition.threshold()).findFirst().orElse(0);
        int maximum = metric == Metric.CONTROLLED_OBJECTIVES
                ? ruleset.layout().objectives().size()
                : (int) Math.min(Integer.MAX_VALUE, ruleset.durationSteps() * Math.max(1, ruleset.layout().objectives().size()));
        session.setVictoryThreshold(metric, Math.max(0, Math.min(maximum, current + delta)));
    }

    private ArenaRuleset saveLocal(Player player, ArenaRulesetEditorSession session) {
        String encoded = session.save(codec);
        player.getPersistentDataContainer().set(savedRulesetKey, PersistentDataType.BYTE_ARRAY,
                encoded.getBytes(StandardCharsets.UTF_8));
        player.sendMessage(Component.text("竞技规则集已保存到玩家数据。"));
        return (ArenaRuleset) codec.load(encoded);
    }

    private void showPreview(Player player, ArenaRulesetValidator.Analysis analysis) {
        player.sendMessage(Component.text("规则预览 " + (analysis.valid() ? "可保存" : "超出预算")
                + " | 格点 " + analysis.cellCount() + " | 可达格点 " + analysis.reachableCellCount()
                + " | 估算工作 " + analysis.estimatedWorkUnits() + "/" + ArenaRulesetValidator.MAX_WORK_UNITS));
        analysis.issues().forEach(issue -> player.sendMessage(Component.text("规则校验：" + issue)));
    }

    private void persistCheckpoint(Player player, ArenaRulesetEditorSession session) {
        player.getPersistentDataContainer().set(checkpointKey, PersistentDataType.BYTE_ARRAY, session.encodeCheckpoint(codec));
    }

    private void submitForReview(Player player, ArenaRuleset ruleset) throws IOException {
        ArenaRuleset submitted = reviews.submit(ruleset, player.getUniqueId());
        player.sendMessage(Component.text("规则集已提交审核，SHA-256 " + submitted.metadata().hash()
                + "。审核者需用其已固定 Ed25519 密钥签名；命令见 docs/DESIGN_EXCHANGE.md。"));
    }

    void submitImported(ArenaRuleset ruleset, String keyFingerprint,
                        org.bukkit.command.CommandSender sender) throws IOException {
        ArenaRuleset submitted = reviews.submitSigned(ruleset, ruleset.metadata().author(), keyFingerprint);
        sender.sendMessage(Component.text("签名已验证，规则集已提交审核，SHA-256 " + submitted.metadata().hash()
                + "。审核者需用其已固定 Ed25519 密钥签名；命令见 docs/DESIGN_EXCHANGE.md。"));
    }

    private ItemStack[] render(ArenaHolder holder) {
        ArenaRuleset ruleset = holder.session.draft();
        ItemStack[] contents = new ItemStack[holder.mapMode ? 45 : SIZE];
        if (!holder.mapMode && ruleset.metadata().author().equals(holder.playerId)) {
            var permissions = ruleset.metadata().permissions();
            contents[PUBLIC_READ] = item(Material.ENDER_EYE, permissions.publicRead() ? "公开读取：开" : "公开读取：关",
                    List.of("启用并通过审核后，此版本可用于公开房间"));
            contents[ALLOW_CLONE] = item(Material.WRITABLE_BOOK, permissions.allowClone() ? "允许克隆：开" : "允许克隆：关",
                    List.of("允许有读取权限的玩家复制此版本"));
        }
        contents[WIDTH_DOWN] = item(Material.ARROW, "缩小宽度", List.of("当前 " + ruleset.width(), "最小 4"));
        contents[WIDTH_UP] = item(Material.SPECTRAL_ARROW, "增加宽度", List.of("当前 " + ruleset.width(), "最大 128"));
        contents[HEIGHT_DOWN] = item(Material.ARROW, "缩小高度", List.of("当前 " + ruleset.height(), "最小 4"));
        contents[HEIGHT_UP] = item(Material.SPECTRAL_ARROW, "增加高度", List.of("当前 " + ruleset.height(), "最大 128"));
        contents[DURATION_DOWN] = item(Material.CLOCK, "缩短时长", List.of("当前 " + ruleset.durationSteps() + " 步", "每次调整 100 步"));
        contents[DURATION_UP] = item(Material.CLOCK, "延长时长", List.of("当前 " + ruleset.durationSteps() + " 步", "每次调整 100 步"));
        contents[UNITS_DOWN] = item(Material.IRON_INGOT, "减少单位预算", List.of("当前 " + ruleset.unitBudget(), "最小 1"));
        contents[UNITS_UP] = item(Material.GOLD_INGOT, "增加单位预算", List.of("当前 " + ruleset.unitBudget(), "最大 64"));
        contents[PREVIEW] = item(Material.SPYGLASS, "验证规则", List.of("检查有限工作量与可达格点"));
        contents[UNDO] = item(Material.SPECTRAL_ARROW, "撤销", List.of(holder.session.canUndo() ? "撤销最近的规则编辑" : "没有可撤销编辑"));
        contents[VERSION] = item(Material.WRITABLE_BOOK, "提升补丁版本", List.of("当前 " + ruleset.metadata().version(), "已发布版本不可覆盖"));
        contents[SAVE] = item(Material.BOOK, "保存草稿", List.of("需要先验证当前规则"));
        contents[PUBLISH] = item(Material.EMERALD, "验证并发布", List.of("发布为不可变版本", "精确依赖与许可会在发布时复核"));
        contents[CLOSE] = item(Material.BARRIER, "关闭", List.of());
        contents[UPGRADE] = item(ruleset.metadata().schemaVersion() == ArenaRuleset.SCHEMA_VERSION
                        ? Material.LIME_STAINED_GLASS_PANE : Material.END_CRYSTAL,
                ruleset.metadata().schemaVersion() == ArenaRuleset.SCHEMA_VERSION ? "竞技地图 v2" : "升级竞技规则格式",
                ruleset.layout() == null ? List.of("加入障碍、队伍出生点和目标", "升级会提升补丁版本")
                        : List.of("胜利条件 " + ruleset.victory().combination(), "目标数量 " + ruleset.layout().objectives().size()));
        if (ruleset.victory() != null) {
            int controlled = victoryThreshold(ruleset, Metric.CONTROLLED_OBJECTIVES);
            int points = victoryThreshold(ruleset, Metric.OBJECTIVE_POINTS);
            contents[6] = item(Material.COMPARATOR, "胜利组合", List.of("当前 " + ruleset.victory().combination(), "点击切换 ANY / ALL"));
            contents[7] = item(Material.REDSTONE_TORCH, "减少控点目标数", List.of("当前 " + controlled, "降至 0 移除该条件"));
            contents[8] = item(Material.REDSTONE, "增加控点目标数", List.of("当前 " + controlled));
            contents[9] = item(Material.CLOCK, "减少目标积分", List.of("当前 " + points, "每次调整 25 分"));
            contents[18] = item(Material.EXPERIENCE_BOTTLE, "增加目标积分", List.of("当前 " + points, "每次调整 25 分"));
        }
        contents[MAP] = item(Material.FILLED_MAP, "编辑竞技地图", List.of(ruleset.layout() == null
                ? "升级到 schema 2 后开放地图编辑" : "编辑障碍、出生位和目标"));
        contents[4] = item(Material.MAP, "竞技规则集", List.of("尺寸 " + ruleset.width() + " × " + ruleset.height(),
                "时长 " + ruleset.durationSteps() + " 步 | 单位预算 " + ruleset.unitBudget(),
                "版本 " + ruleset.metadata().version(), ruleset.victory() == null ? "胜利条件 未设置"
                        : "胜利 " + ruleset.victory().combination() + " | 控点 " + victoryThreshold(ruleset, Metric.CONTROLLED_OBJECTIVES)
                        + " | 积分 " + victoryThreshold(ruleset, Metric.OBJECTIVE_POINTS)));
        if (holder.mapMode) renderMap(holder, contents);
        return contents;
    }

    private static int victoryThreshold(ArenaRuleset ruleset, Metric metric) {
        if (ruleset.victory() == null) return 0;
        return ruleset.victory().conditions().stream().filter(condition -> condition.metric() == metric)
                .mapToInt(condition -> condition.threshold()).findFirst().orElse(0);
    }

    private void renderMap(ArenaHolder holder, ItemStack[] contents) {
        ArenaRuleset ruleset = holder.session.draft();
        contents[X_PREVIOUS] = item(Material.ARROW, "西移", List.of("X " + holder.viewX));
        contents[X_NEXT] = item(Material.ARROW, "东移", List.of("X " + holder.viewX));
        contents[Y_PREVIOUS] = item(Material.ARROW, "北移", List.of("Y " + holder.viewY));
        contents[Y_NEXT] = item(Material.ARROW, "南移", List.of("Y " + holder.viewY));
        contents[MODE_PREVIOUS] = item(Material.SPECTRAL_ARROW, "上一种地图元素", List.of(MAP_MODES[Math.floorMod(holder.mode - 1, MAP_MODES.length)]));
        contents[MODE_NEXT] = item(Material.SPECTRAL_ARROW, "下一种地图元素", List.of(MAP_MODES[(holder.mode + 1) % MAP_MODES.length]));
        contents[MAP_PREVIEW] = item(Material.SPYGLASS, "验证地图与规则", List.of("检查出生点到目标的路线"));
        contents[MAP_UNDO] = item(Material.SPECTRAL_ARROW, "撤销地图编辑", List.of(holder.session.canUndo() ? "撤销最近的更改" : "没有可撤销编辑"));
        contents[MAP_SAVE] = item(Material.BOOK, "保存地图草稿", List.of("保存当前规则版本"));
        contents[MAP_PUBLISH] = item(Material.EMERALD, "发布地图版本", List.of("重新验证并写入不可变版本"));
        contents[MAP_BACK] = item(Material.OAK_DOOR, "返回规则", List.of());
        contents[MAP_CLOSE] = item(Material.BARRIER, "关闭", List.of());
        contents[4] = item(Material.FILLED_MAP, "竞技地图", List.of("X " + holder.viewX + " / " + (ruleset.width() - 1)
                + " | Y " + holder.viewY + " / " + (ruleset.height() - 1), "元素 " + MAP_MODES[holder.mode]));
        for (int index = 0; index < MAP_SLOTS.length; index++) {
            int x = holder.viewX + index % 5 - 2;
            int y = holder.viewY + index / 5 - 1;
            contents[MAP_SLOTS[index]] = x < 0 || y < 0 || x >= ruleset.width() || y >= ruleset.height()
                    ? item(Material.GRAY_STAINED_GLASS_PANE, "边界外", List.of())
                    : renderCell(ruleset, new Cell(x, y), MAP_MODES[holder.mode]);
        }
    }

    private static ItemStack renderCell(ArenaRuleset ruleset, Cell cell, String mode) {
        List<String> details = new java.util.ArrayList<>(List.of("(" + cell.x() + ", " + cell.y() + ")"));
        Material material = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
        boolean occupied = false;
        if (ruleset.layout().obstacles().contains(cell)) {
            material = Material.BLACK_STAINED_GLASS_PANE;
            details.add("障碍");
            occupied = true;
        }
        for (var team : ruleset.layout().teams()) {
            if (team.spawns().contains(cell)) {
                material = team.id().equals("red") ? Material.RED_BED : team.id().equals("blue")
                        ? Material.BLUE_BED : Material.WHITE_BED;
                details.add(team.id() + " 出生位");
                occupied = true;
            }
        }
        if (ruleset.layout().objectives().contains(cell)) {
            material = Material.BEACON;
            details.add("目标");
            occupied = true;
        }
        if (!occupied) details.add("可放置 " + mode);
        return item(material, occupied ? "地图格" : "空地", details);
    }

    private ArenaRuleset loadStarter() {
        try (var input = getClass().getResourceAsStream("/examples/arena_ruleset-v2.json")) {
            if (input == null) throw new IllegalStateException("Bundled starter arena ruleset is missing");
            DesignDocument document = codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!(document instanceof ArenaRuleset ruleset)) throw new IllegalStateException("Starter is not an arena ruleset");
            return ruleset;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load the starter arena ruleset", failure);
        }
    }

    private ArenaRuleset loadLegacyStarter() {
        try (var input = getClass().getResourceAsStream("/examples/arena_ruleset.json")) {
            if (input == null) throw new IllegalStateException("Bundled legacy arena ruleset is missing");
            DesignDocument document = codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!(document instanceof ArenaRuleset ruleset)) throw new IllegalStateException("Legacy starter is not an arena ruleset");
            return ruleset;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load the legacy starter arena ruleset", failure);
        }
    }

    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(lore.stream().map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class ArenaHolder implements InventoryHolder {
        private final java.util.UUID playerId;
        private final ArenaRulesetEditorSession session;
        private Inventory inventory;
        private boolean mapMode;
        private int viewX;
        private int viewY;
        private int mode;

        private ArenaHolder(java.util.UUID playerId, ArenaRulesetEditorSession session) {
            this.playerId = playerId;
            this.session = session;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
