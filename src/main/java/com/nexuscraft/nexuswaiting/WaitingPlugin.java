package com.nexuscraft.nexuswaiting;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

public final class WaitingPlugin extends JavaPlugin {

    private final WaitingConfig cfg = new WaitingConfig();
    private WaitingStore store;
    private WaitingService service;
    private BukkitTask checkTask;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg.load(getConfig());
        store = new WaitingStore(new File(getDataFolder(), "timers.tsv"), new File(getDataFolder(), "profiles.tsv"), getLogger()::warning);
        store.load();
        service = new WaitingService(this, cfg, store,
                new NexusGateProbe(() -> Bukkit.getPluginManager().getPlugin("NexusGate"), getLogger()::warning),
                System::currentTimeMillis, getLogger()::warning);
        connectDiscord();
        getServer().getServicesManager().register(WaitingApi.class, new WaitingApiImpl(service), this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new WaitingListener(service), this);
        WaitingCommand command = new WaitingCommand(service, this::reload, System::currentTimeMillis);
        getCommand("waiting").setExecutor(command);
        getCommand("waiting").setTabCompleter(command);
        long period = cfg.checkIntervalSeconds * 20L;
        checkTask = getServer().getScheduler().runTaskTimer(this, service::tick, 100L, period);
        saveTask = getServer().getScheduler().runTaskTimer(this, this::autosave, 6000L, 6000L);
        getLogger().info("NexusWaiting enabled: " + store.timerCount() + " timers on record, errands "
                + (cfg.errandsEnabled ? "on" : "off") + ", Discord pings " + (cfg.discordReady() ? "on" : "off") + ".");
        for (String w : cfg.warnings) {
            getLogger().warning(w);
        }
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        if (store != null) {
            store.flush();
        }
    }

    private void autosave() {
        store.cleanup(System.currentTimeMillis(), cfg.cleanupDays * PingPolicy.DAY);
        store.flush();
    }

    /** (Re)creates the Discord sender from the config; never logs the webhook address. */
    private void connectDiscord() {
        if (!cfg.discordReady()) {
            service.setWebhook(null);
            return;
        }
        try {
            service.setWebhook(new HttpWebhook(cfg.webhookUrl));
        } catch (IllegalArgumentException ex) {
            service.setWebhook(null);
            getLogger().warning("discord.webhook-url could not be used; Discord pings are off.");
        }
    }

    private void reload() {
        reloadConfig();
        cfg.load(getConfig());
        connectDiscord();
        for (String w : cfg.warnings) {
            getLogger().warning(w);
        }
    }
}
