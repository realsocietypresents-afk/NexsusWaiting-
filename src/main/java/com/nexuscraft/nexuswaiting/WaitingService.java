package com.nexuscraft.nexuswaiting;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** All the decisions: what is ready, who is told how, the login popup, errands, Discord linking. */
final class WaitingService {

    static final String PERMISSION_SLOTS = "nexuswaiting.slots.";
    static final long TEST_COOLDOWN_MS = 60_000L;

    private final Plugin plugin;
    private final WaitingConfig cfg;
    private final WaitingStore store;
    private final LoginGate gate;
    private final LongSupplier clock;
    private final Consumer<String> warn;
    private Webhook webhook;
    /** When each server-wide Discord ping went out, newest last (for the per-hour limit). */
    private final ArrayDeque<Long> recentPings = new ArrayDeque<>();
    private final Map<UUID, Long> lastTest = new HashMap<>();

    WaitingService(Plugin plugin, WaitingConfig cfg, WaitingStore store, LoginGate gate, LongSupplier clock, Consumer<String> warn) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.store = store;
        this.gate = gate;
        this.clock = clock;
        this.warn = warn;
    }

    WaitingConfig cfg() {
        return cfg;
    }

    WaitingStore store() {
        return store;
    }

    /** The thing that posts to Discord (null = no Discord). */
    void setWebhook(Webhook webhook) {
        this.webhook = webhook;
    }

    // ------------------------------------------------------------------ timers other plugins and staff add

    boolean set(UUID id, String key, String label, long readyAtMillis) {
        String text = label == null || label.isBlank() ? key : label;
        return store.put(id, new WaitEntry(key, text, readyAtMillis, WaitEntry.SOURCE_API, ""), cfg.maxTimersPerPlayer);
    }

    boolean clear(UUID id, String key) {
        return store.remove(id, key);
    }

    boolean has(UUID id, String key) {
        return store.get(id, key) != null;
    }

    // ------------------------------------------------------------------ joining and the popup

    void onJoin(Player p) {
        UUID id = p.getUniqueId();
        Profile prof = store.profile(id);
        if (!p.getName().equals(prof.name)) {
            prof.name = p.getName();
            store.markDirty();
        }
        long now = clock.getAsLong();
        int ready = 0;
        for (WaitEntry e : store.all(id)) {
            if (e.ready(now)) {
                ready++;
                store.setNotified(id, e.key, true); // this login is the news
            }
        }
        if (ready > 0 && cfg.notifyOnline) {
            say(p, cfg.msg("login").replace("{n}", String.valueOf(ready)));
        }
        if (prof.popup && cfg.popup.shouldPopup(ready)) {
            long ticks = cfg.delaySeconds * 20L;
            if (ticks <= 0) {
                attempt(p, 0);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(p, 0), ticks);
            }
        }
    }

    /** One try at popping the list up; if the player is not ready (logging in, in another menu) it tries again in a second. */
    void attempt(Player p, long waitedTicks) {
        if (!p.isOnline()) {
            return;
        }
        Profile prof = store.profile(p.getUniqueId());
        if (!prof.popup || !cfg.popup.shouldPopup(store.readyCount(p.getUniqueId(), clock.getAsLong()))) {
            return;
        }
        boolean busy = (cfg.waitForLogin && gate.waiting(p.getUniqueId())) || p.isDead() || !free(p);
        if (busy) {
            if (waitedTicks < cfg.loginTimeoutSeconds * 20L) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> attempt(p, waitedTicks + 20L), 20L);
            }
            return;
        }
        open(p);
    }

    /** True when the player has no menu open (the inventory they "have open" is just their own). */
    static boolean free(Player p) {
        return p.getOpenInventory() == null || p.getOpenInventory().getTopInventory() == null
                || p.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING;
    }

    // ------------------------------------------------------------------ the menus

    void open(Player p) {
        p.openInventory(WaitingMenu.ledger(cfg, store, p.getUniqueId(), clock.getAsLong(), slotsFor(p)).getInventory());
    }

    private void openPicker(Player p, int errandSlot) {
        p.openInventory(WaitingMenu.picker(cfg, errandSlot).getInventory());
    }

    /** Opens a menu on the next tick (never from inside the click that asked for it). */
    private void later(Player p, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                action.run();
            }
        });
    }

    void click(Player p, WaitingMenu menu, int slot) {
        WaitingMenu.Action a = menu.at(slot);
        if (a == null) {
            return;
        }
        UUID id = p.getUniqueId();
        switch (a.type) {
            case DISMISS -> {
                WaitEntry e = store.get(id, a.arg);
                if (e != null && e.ready(clock.getAsLong())) {
                    store.remove(id, a.arg);
                    say(p, cfg.msg("dismissed"));
                }
                later(p, () -> open(p));
            }
            case NOT_READY -> {
                WaitEntry e = store.get(id, a.arg);
                if (e != null) {
                    say(p, cfg.msg("not-ready").replace("{time}", TimeText.left(e.readyAt - clock.getAsLong())));
                }
            }
            case ERRAND_RUNNING -> {
                WaitEntry e = store.get(id, errandKey(a.n));
                if (e != null) {
                    say(p, cfg.msg("not-ready").replace("{time}", TimeText.left(e.readyAt - clock.getAsLong())));
                }
            }
            case ERRAND_SEND -> later(p, () -> openPicker(p, a.n));
            case ERRAND_CLAIM -> {
                claim(p, a.n);
                later(p, () -> open(p));
            }
            case PICK -> {
                start(p, a.n, a.arg);
                later(p, () -> open(p));
            }
            case BACK -> later(p, () -> open(p));
            case DISCORD -> discordTap(p);
            default -> {
            }
        }
    }

    private void discordTap(Player p) {
        Profile prof = store.profile(p.getUniqueId());
        if (prof.linked()) {
            setDiscordEnabled(p, !prof.enabled);
            later(p, () -> open(p));
        } else {
            p.closeInventory();
            say(p, cfg.msg("discord-status-none"));
        }
    }

    // ------------------------------------------------------------------ errands

    static String errandKey(int slot) {
        return "errand:" + slot;
    }

    /** How many scouts this player may have out: the server's base number, or more with a nexuswaiting.slots.N permission. */
    int slotsFor(Player p) {
        int slots = cfg.baseSlots;
        for (int n = cfg.maxSlots; n > slots; n--) {
            if (p.hasPermission(PERMISSION_SLOTS + n)) {
                return n;
            }
        }
        return slots;
    }

    /** Sends a scout from slot {@code slot} to a destination. Returns whether it left. */
    boolean start(Player p, int slot, String destinationId) {
        if (!cfg.errandsEnabled) {
            say(p, cfg.msg("errand-off"));
            return false;
        }
        Destination d = cfg.destination(destinationId);
        if (d == null || slot < 1 || slot > slotsFor(p)) {
            return false;
        }
        UUID id = p.getUniqueId();
        if (store.get(id, errandKey(slot)) != null) {
            say(p, cfg.msg("errand-busy"));
            return false;
        }
        String place = ColorText.strip(d.name);
        String label = cfg.msg("errand-label").replace("{place}", place);
        store.put(id, new WaitEntry(errandKey(slot), label, clock.getAsLong() + d.millis(), WaitEntry.SOURCE_ERRAND, d.id), Integer.MAX_VALUE);
        say(p, cfg.msg("errand-sent").replace("{place}", place).replace("{time}", TimeText.left(d.millis())));
        return true;
    }

    /** The first free scout slot, or 0 when every scout is out. */
    int freeSlot(Player p) {
        int slots = slotsFor(p);
        for (int s = 1; s <= slots; s++) {
            if (store.get(p.getUniqueId(), errandKey(s)) == null) {
                return s;
            }
        }
        return 0;
    }

    /** Collects a finished errand. Returns whether the haul was handed over. */
    boolean claim(Player p, int slot) {
        UUID id = p.getUniqueId();
        WaitEntry e = store.get(id, errandKey(slot));
        long now = clock.getAsLong();
        if (e == null || !e.isErrand()) {
            say(p, cfg.msg("errand-none"));
            return false;
        }
        if (!e.ready(now)) {
            say(p, cfg.msg("not-ready").replace("{time}", TimeText.left(e.readyAt - now)));
            return false;
        }
        Destination d = cfg.destination(e.data);
        if (d == null) { // the place was taken out of the config while the scout was away
            store.remove(id, e.key);
            say(p, cfg.msg("dismissed"));
            return false;
        }
        Haul haul = d.roll(new Random(e.readyAt * 31L + id.getLeastSignificantBits() + d.id.hashCode()));
        int needed = haul.slotsNeeded(m -> new ItemStack(m).getMaxStackSize());
        if (needed > emptySlots(p)) {
            say(p, cfg.msg("errand-room").replace("{n}", String.valueOf(needed)));
            return false;
        }
        store.remove(id, e.key);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Integer> item : haul.items.entrySet()) {
            p.getInventory().addItem(new ItemStack(item.getKey(), item.getValue()));
            parts.add(item.getValue() + "x " + pretty(item.getKey()));
        }
        for (String command : haul.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", p.getName()));
        }
        if (!haul.commands.isEmpty()) {
            parts.add("a reward");
        }
        if (haul.jackpot) {
            say(p, cfg.msg("errand-jackpot"));
        }
        say(p, cfg.msg("errand-claim").replace("{place}", ColorText.strip(d.name)).replace("{haul}", String.join(", ", parts)));
        if (!haul.story.isEmpty()) {
            p.sendMessage(ColorText.translate("&7&o" + haul.story));
        }
        return true;
    }

    static int emptySlots(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType() == Material.AIR) {
                n++;
            }
        }
        return n;
    }

    static String pretty(Material m) {
        StringBuilder b = new StringBuilder();
        for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ telling people (runs every few seconds)

    void tick() {
        long now = clock.getAsLong();
        Map<UUID, List<WaitEntry>> byPlayer = new LinkedHashMap<>();
        for (WaitingStore.Due d : store.due(now)) {
            byPlayer.computeIfAbsent(d.player, k -> new ArrayList<>()).add(d.entry);
        }
        for (Map.Entry<UUID, List<WaitEntry>> e : byPlayer.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && p.isOnline()) {
                tellOnline(p, e.getValue());
            } else {
                tellOffline(e.getKey(), e.getValue(), now);
            }
        }
    }

    private void tellOnline(Player p, List<WaitEntry> entries) {
        if (cfg.notifyOnline) {
            say(p, cfg.msg("ready-online").replace("{what}", what(entries)));
        }
        for (WaitEntry e : entries) {
            store.setNotified(p.getUniqueId(), e.key, true);
        }
    }

    private static String what(List<WaitEntry> entries) {
        List<String> labels = new ArrayList<>();
        for (WaitEntry e : entries) {
            labels.add(ColorText.strip(e.label));
        }
        StringBuilder b = new StringBuilder();
        int shown = Math.min(labels.size(), DiscordMessage.LABELS_SHOWN);
        for (int i = 0; i < shown; i++) {
            b.append(i > 0 ? "; " : "").append(labels.get(i));
        }
        if (labels.size() > shown) {
            b.append("; +").append(labels.size() - shown).append(" more");
        }
        return b.toString();
    }

    private void tellOffline(UUID id, List<WaitEntry> entries, long now) {
        List<WaitEntry> fresh = new ArrayList<>();
        for (WaitEntry e : entries) {
            if (now - e.readyAt > cfg.skipOlderThanHours * PingPolicy.HOUR) {
                store.setNotified(id, e.key, true); // too old to be news
            } else {
                fresh.add(e);
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        Profile prof = store.peekProfile(id);
        if (webhook == null || !cfg.discordReady() || prof == null || !prof.linked() || !prof.enabled) {
            for (WaitEntry e : fresh) {
                store.setNotified(id, e.key, true); // nobody to ping; the login popup will say it
            }
            return;
        }
        if (!PingPolicy.allowed(prof, now, cfg.minMinutesBetweenPings, cfg.maxPingsPerDay)
                || !PingPolicy.serverHasRoom(recentPings, now, cfg.maxPingsPerHour)) {
            return; // stays unannounced; asked again at the next check
        }
        List<String> labels = new ArrayList<>();
        for (WaitEntry e : fresh) {
            labels.add(e.label);
        }
        String text = DiscordMessage.compose(cfg.discordMessage, prof.name, cfg.serverName, labels);
        String body = DiscordMessage.json(prof.discordId, text, cfg.discordUsername);
        PingPolicy.record(prof, now);
        recentPings.addLast(now);
        List<String> keys = new ArrayList<>();
        for (WaitEntry e : fresh) {
            store.setNotified(id, e.key, true);
            keys.add(e.key);
        }
        store.markDirty();
        webhook.post(body, ok -> {
            if (!ok) {
                // may run on the HTTP client's thread: hop back to the server thread before touching anything
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String k : keys) {
                        store.setNotified(id, k, false);
                    }
                    warn.accept("A Discord ping did not go through; it will be tried again later.");
                });
            }
        });
    }

    // ------------------------------------------------------------------ Discord linking

    void link(Player p, String discordId) {
        if (!cfg.discordReady()) {
            say(p, cfg.msg("discord-off-server"));
            return;
        }
        String id = discordId == null ? "" : discordId.trim();
        if (!DiscordMessage.validUserId(id)) {
            say(p, cfg.msg("discord-bad-id"));
            return;
        }
        Profile prof = store.profile(p.getUniqueId());
        prof.discordId = id;
        prof.enabled = true;
        store.markDirty();
        say(p, cfg.msg("discord-linked"));
    }

    boolean unlink(UUID id) {
        Profile prof = store.peekProfile(id);
        if (prof == null || !prof.linked()) {
            return false;
        }
        prof.discordId = "";
        store.markDirty();
        return true;
    }

    void setDiscordEnabled(Player p, boolean on) {
        Profile prof = store.profile(p.getUniqueId());
        if (!prof.linked()) {
            say(p, cfg.msg("discord-not-linked"));
            return;
        }
        prof.enabled = on;
        store.markDirty();
        say(p, cfg.msg(on ? "discord-on" : "discord-off"));
    }

    void setPopup(Player p, boolean on) {
        store.profile(p.getUniqueId()).popup = on;
        store.markDirty();
        say(p, cfg.msg(on ? "popup-on" : "popup-off"));
    }

    void discordStatus(Player p) {
        Profile prof = store.profile(p.getUniqueId());
        if (!cfg.discordReady()) {
            say(p, cfg.msg("discord-off-server"));
        } else if (prof.linked()) {
            say(p, cfg.msg("discord-status-linked").replace("{state}", prof.enabled ? "on" : "off"));
        } else {
            say(p, cfg.msg("discord-status-none"));
        }
    }

    /** Sends the player one test ping to their linked Discord account (at most one a minute). */
    void test(Player p) {
        Profile prof = store.profile(p.getUniqueId());
        if (!cfg.discordReady() || webhook == null) {
            say(p, cfg.msg("discord-off-server"));
            return;
        }
        if (!prof.linked()) {
            say(p, cfg.msg("discord-not-linked"));
            return;
        }
        long now = clock.getAsLong();
        Long last = lastTest.get(p.getUniqueId());
        if (last != null && now - last < TEST_COOLDOWN_MS) {
            say(p, cfg.msg("discord-test-wait"));
            return;
        }
        lastTest.put(p.getUniqueId(), now);
        String text = DiscordMessage.compose("This is a test ping from {server}.", prof.name, cfg.serverName, List.of());
        webhook.post(DiscordMessage.json(prof.discordId, text, cfg.discordUsername), ok -> {
            if (!ok) {
                Bukkit.getScheduler().runTask(plugin, () -> warn.accept("The Discord test ping for " + p.getName() + " did not go through."));
            }
        });
        say(p, cfg.msg("discord-test-sent"));
    }

    // ------------------------------------------------------------------ misc

    void quit(Player p) {
        lastTest.remove(p.getUniqueId());
        store.flush();
    }

    void say(Player p, String text) {
        p.sendMessage(ColorText.translate(cfg.msg("prefix") + text));
    }
}
