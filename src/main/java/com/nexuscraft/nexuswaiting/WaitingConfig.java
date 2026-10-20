package com.nexuscraft.nexuswaiting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everything in config.yml, checked: bad values fall back to something safe and add a warning. */
final class WaitingConfig {

    /** Every message the plugin says, with its built-in text (config.yml ships the same text). */
    static final Map<String, String> DEFAULT_MESSAGES = new LinkedHashMap<>();

    static {
        Map<String, String> m = DEFAULT_MESSAGES;
        m.put("prefix", "&d&l[Waiting] &r");
        m.put("login", "&7You have &f{n}&7 thing(s) waiting for you. &f/waiting&7 opens the list.");
        m.put("ready-online", "&a{what}&7 Type &f/waiting&7 to see it.");
        m.put("dismissed", "&7Cleared from your list.");
        m.put("not-ready", "&7Not ready yet: &f{time}&7 to go.");
        m.put("errand-label", "Your scout is back from {place}");
        m.put("errand-sent", "&aYou sent a scout to &f{place}&a. Back in &f{time}&a.");
        m.put("errand-busy", "&cAll your scouts are already out.");
        m.put("errand-none", "&7You have no scout out there.");
        m.put("errand-claim", "&aYour scout returned from &f{place}&a with: &f{haul}");
        m.put("errand-jackpot", "&6&lLucky find! &r&7Your scout came back with something special.");
        m.put("errand-room", "&cMake room first: you need &f{n}&c empty inventory slot(s) to collect this.");
        m.put("errand-off", "&7Errands are turned off on this server.");
        m.put("discord-off-server", "&7Discord pings are not set up on this server.");
        m.put("discord-linked", "&aLinked. You get a Discord ping when something is ready while you are offline. &f/waiting discord test&a sends one now.");
        m.put("discord-bad-id", "&cThat is not a Discord user ID. It is 17 to 20 digits: in Discord turn on Developer Mode, right-click your name, Copy User ID.");
        m.put("discord-unlinked", "&7Discord unlinked.");
        m.put("discord-on", "&aDiscord pings are on.");
        m.put("discord-off", "&7Discord pings are off. &f/waiting discord on&7 turns them back on.");
        m.put("discord-test-sent", "&aSent a test ping. Check Discord.");
        m.put("discord-test-wait", "&7Wait a minute between tests.");
        m.put("discord-not-linked", "&7Link first: &f/waiting discord link <your Discord user ID>");
        m.put("discord-status-linked", "&7Discord: linked, pings are &f{state}&7.");
        m.put("discord-status-none", "&7Discord: not linked. &f/waiting discord link <your Discord user ID>");
        m.put("popup-on", "&aThe list pops up when you log in and something is ready.");
        m.put("popup-off", "&7The list will not pop up by itself. &f/waiting&7 still opens it.");
        m.put("empty", "&7Nothing is waiting for you right now.");
    }

    PopupMode popup = PopupMode.WHEN_READY;
    int delaySeconds = 8;
    boolean waitForLogin = true;
    int loginTimeoutSeconds = 180;
    int checkIntervalSeconds = 30;
    boolean notifyOnline = true;
    int cleanupDays = 30;
    int maxTimersPerPlayer = 40;

    String title = "&d&lWaiting for you";
    boolean filler = true;
    Material fillerIcon = Material.GRAY_STAINED_GLASS_PANE;

    boolean discordEnabled;
    String webhookUrl = "";
    int minMinutesBetweenPings = 30;
    int maxPingsPerDay = 3;
    int maxPingsPerHour = 30;
    String discordUsername = "RealSociety";
    String serverName = "RealSociety";
    String discordMessage = "Something is waiting for you on {server}: {what}";
    int skipOlderThanHours = 24;

    boolean errandsEnabled = true;
    int baseSlots = 1;
    int maxSlots = 4;
    final List<Destination> destinations = new ArrayList<>();

    final Map<String, String> messages = new LinkedHashMap<>();
    final List<String> warnings = new ArrayList<>();

    /** True when pings can actually go out: switched on and a real webhook address. */
    boolean discordReady() {
        return discordEnabled && DiscordMessage.validWebhook(webhookUrl);
    }

    String msg(String key) {
        String own = messages.get(key);
        return own != null ? own : DEFAULT_MESSAGES.getOrDefault(key, key);
    }

    Destination destination(String id) {
        for (Destination d : destinations) {
            if (d.id.equals(id)) {
                return d;
            }
        }
        return null;
    }

    void load(ConfigurationSection c) {
        warnings.clear();
        destinations.clear();
        messages.clear();
        popup = PopupMode.parse(c.getString("login.popup", "when-ready"), PopupMode.WHEN_READY);
        if (c.isSet("login.popup") && PopupMode.parse(c.getString("login.popup"), null) == null) {
            warnings.add("login.popup '" + c.getString("login.popup") + "' is not when-ready, always or never; using when-ready.");
        }
        delaySeconds = clamp(c.getInt("login.delay-seconds", 8), 0, 600);
        waitForLogin = c.getBoolean("login.wait-for-login", true);
        loginTimeoutSeconds = clamp(c.getInt("login.timeout-seconds", 180), 5, 3600);
        checkIntervalSeconds = clamp(c.getInt("check-interval-seconds", 30), 5, 600);
        notifyOnline = c.getBoolean("notify-online", true);
        cleanupDays = clamp(c.getInt("cleanup-days", 30), 1, 3650);
        maxTimersPerPlayer = clamp(c.getInt("max-timers-per-player", 40), 1, 500);
        title = c.getString("menu.title", title);
        filler = c.getBoolean("menu.filler", true);
        Material f = Material.matchMaterial(c.getString("menu.filler-icon", "GRAY_STAINED_GLASS_PANE"));
        fillerIcon = f == null ? Material.GRAY_STAINED_GLASS_PANE : f;

        discordEnabled = c.getBoolean("discord.enabled", false);
        webhookUrl = c.getString("discord.webhook-url", "").trim();
        minMinutesBetweenPings = clamp(c.getInt("discord.min-minutes-between-pings", 30), 1, 1440);
        maxPingsPerDay = clamp(c.getInt("discord.max-pings-per-day", 3), 1, 50);
        maxPingsPerHour = clamp(c.getInt("discord.max-pings-per-hour-server", 30), 1, 1000);
        discordUsername = c.getString("discord.username", "RealSociety");
        serverName = c.getString("discord.server-name", "RealSociety");
        discordMessage = c.getString("discord.message", discordMessage);
        skipOlderThanHours = clamp(c.getInt("discord.skip-older-than-hours", 24), 1, 720);
        if (discordEnabled && !DiscordMessage.validWebhook(webhookUrl)) {
            warnings.add(webhookUrl.isEmpty()
                    ? "discord.enabled is true but discord.webhook-url is empty; Discord pings are off."
                    : "discord.webhook-url does not look like a Discord webhook address (https://discord.com/api/webhooks/...); Discord pings are off.");
        }

        errandsEnabled = c.getBoolean("errands.enabled", true);
        maxSlots = clamp(c.getInt("errands.max-slots", 4), 1, 4);
        baseSlots = clamp(c.getInt("errands.slots", 1), 1, maxSlots);
        ConfigurationSection dests = c.getConfigurationSection("errands.destinations");
        if (dests != null) {
            for (String id : dests.getKeys(false)) {
                ConfigurationSection s = dests.getConfigurationSection(id);
                if (s != null && destinations.size() < 7) {
                    addDestination(id, s);
                }
            }
        }
        if (errandsEnabled && destinations.isEmpty()) {
            warnings.add("errands.enabled is true but no destination is usable; errands are off.");
            errandsEnabled = false;
        }

        for (String key : DEFAULT_MESSAGES.keySet()) {
            String v = c.getString("messages." + key);
            if (v != null) {
                messages.put(key, v);
            }
        }
    }

    private void addDestination(String rawId, ConfigurationSection s) {
        String id = rawId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty() || destination(id) != null) {
            warnings.add("Destination '" + rawId + "' has an unusable or repeated id; skipped.");
            return;
        }
        List<Reward> rewards = rewards(id, s.getConfigurationSection("rewards"));
        if (rewards.isEmpty()) {
            warnings.add("Destination '" + id + "' has no usable rewards; skipped.");
            return;
        }
        Material icon = Material.matchMaterial(s.getString("icon", "COMPASS"));
        if (icon == null) {
            warnings.add("Destination '" + id + "': unknown icon '" + s.getString("icon") + "'; using COMPASS.");
            icon = Material.COMPASS;
        }
        int percent = clamp(s.getInt("jackpot.chance-percent", 0), 0, 100);
        List<Reward> jackpot = rewards(id + " jackpot", s.getConfigurationSection("jackpot.rewards"));
        destinations.add(new Destination(id, s.getString("name", id), icon, clamp(s.getInt("minutes", 60), 1, 10080),
                new ArrayList<>(s.getStringList("lore")), clamp(s.getInt("rolls", 2), 1, 6), rewards,
                percent, jackpot, new ArrayList<>(s.getStringList("stories"))));
    }

    private List<Reward> rewards(String where, ConfigurationSection sec) {
        List<Reward> out = new ArrayList<>();
        if (sec == null) {
            return out;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(key);
            if (r == null) {
                continue;
            }
            String command = r.getString("command", "").trim();
            while (command.startsWith("/")) {
                command = command.substring(1).trim();
            }
            int weight = r.getInt("weight", 1);
            if (!command.isEmpty()) {
                out.add(new Reward(null, command, 1, 1, weight));
                continue;
            }
            Material m = Material.matchMaterial(r.getString("item", ""));
            if (m == null || m == Material.AIR) {
                warnings.add("Destination '" + where + "', reward '" + key + "': unknown item '" + r.getString("item", "") + "'; skipped.");
                continue;
            }
            int min = r.getInt("min", 1);
            out.add(new Reward(m, "", min, r.getInt("max", min), weight));
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
