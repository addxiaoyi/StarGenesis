package dev.stargenesis.testfixture;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LabDisableTrigger extends JavaPlugin {
    private final List<String> runtimeSamples = Collections.synchronizedList(new ArrayList<>());
    private ScheduledTask sampleTask;

    @Override
    public void onEnable() {
        sampleTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this,
                task -> recordRuntimeSample(), 1, 100);
        getCommand("disable-star-genesis").setExecutor((sender, command, label, arguments) -> {
            Plugin target = getServer().getPluginManager().getPlugin("StarGenesis");
            if (target == null || !target.isEnabled()) {
                sender.sendMessage("StarGenesis is not enabled");
                return true;
            }
            recordRuntimeSample();
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.writeString(getDataFolder().toPath().resolve("runtime-sample.json"), runtimeSampleDocument());
            } catch (IOException failure) {
                throw new IllegalStateException("Could not persist Folia runtime sample", failure);
            }
            getServer().getPluginManager().disablePlugin(target);
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.writeString(getDataFolder().toPath().resolve("disable-completed.txt"), "disabled\n");
            } catch (IOException failure) {
                throw new IllegalStateException("Could not persist disable fixture completion", failure);
            }
            Bukkit.broadcastMessage("LAB_DISABLE_TRIGGER_COMPLETED");
            sender.sendMessage("Disable fixture completed for StarGenesis");
            return true;
        });
    }

    private void recordRuntimeSample() {
        double[] tps = Bukkit.getTPS();
        String sample = String.format(Locale.ROOT,
                "{\"sampledAt\":\"%s\",\"onlinePlayers\":%d,\"tps\":[%.3f,%.3f,%.3f],\"averageTickMillis\":%.3f}",
                java.time.Instant.now(), Bukkit.getOnlinePlayers().size(), tps[0], tps[1], tps[2],
                Bukkit.getAverageTickTime());
        synchronized (runtimeSamples) {
            if (runtimeSamples.size() < 2048) runtimeSamples.add(sample);
        }
    }

    private String runtimeSampleDocument() {
        synchronized (runtimeSamples) {
            return "{\"samples\":[" + String.join(",", runtimeSamples)
                    + "],\"sampleCount\":" + runtimeSamples.size() + "}\n";
        }
    }

    @Override
    public void onDisable() {
        if (sampleTask != null) sampleTask.cancel();
    }
}
