package com.nexuscraft.nexuswaiting;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static com.nexuscraft.nexuswaiting.Kit.expect;

public class FlowTest {

    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    static final String HOOK = "https://discord.com/api/webhooks/1/abc";
    static final String DISCORD_ID = "123456789012345678";

    static final class Other implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** One server's worth of wiring. */
    static final class Env {
        final Kit.TestPlugin plugin = new Kit.TestPlugin();
        final WaitingConfig cfg = new WaitingConfig();
        final WaitingStore store;
        final WaitingService svc;
        final WaitingListener listener;
        final WaitingCommand cmd;
        final Set<UUID> logging = new HashSet<>();
        final long[] now = {1_000_000_000_000L};
        final File dir;
        final List<String> posts = new ArrayList<>();
        final List<Consumer<Boolean>> pending = new ArrayList<>();
        final List<String> warns = new ArrayList<>();
        /** What the fake Discord answers right away; null = keep the callback in {@link #pending}. */
        Boolean answer = Boolean.TRUE;
        int reloads;

        Env(Kit.MapSection extra) throws Exception {
            dir = Files.createTempDirectory("nwait-flow").toFile();
            Kit.MapSection m = LogicTest.base()
                    .put("errands.destinations.mine.name", "&7The Mine").put("errands.destinations.mine.icon", "IRON_PICKAXE")
                    .put("errands.destinations.mine.minutes", 60).put("errands.destinations.mine.rolls", 1)
                    .put("errands.destinations.mine.rewards.coal.item", "COAL").put("errands.destinations.mine.rewards.coal.min", 2).put("errands.destinations.mine.rewards.coal.max", 2)
                    .put("errands.destinations.mine.jackpot.chance-percent", 100)
                    .put("errands.destinations.mine.jackpot.rewards.d.item", "DIAMOND")
                    .put("errands.destinations.mine.stories", List.of("It was dark."))
                    .put("errands.destinations.bank.name", "The Bank").put("errands.destinations.bank.minutes", 5).put("errands.destinations.bank.rolls", 1)
                    .put("errands.destinations.bank.rewards.pay.command", "eco give {player} 5")
                    .put("discord.enabled", true).put("discord.webhook-url", HOOK);
            if (extra != null) {
                m.values.putAll(extra.values);
            }
            cfg.load(m);
            store = new WaitingStore(new File(dir, "timers.tsv"), new File(dir, "profiles.tsv"), s -> System.out.println("store warn: " + s));
            svc = new WaitingService(plugin, cfg, store, id -> logging.contains(id), () -> now[0], warns::add);
            svc.setWebhook((json, done) -> {
                posts.add(json);
                if (answer == null) {
                    pending.add(done);
                } else {
                    done.accept(answer);
                }
            });
            listener = new WaitingListener(svc);
            cmd = new WaitingCommand(svc, () -> reloads++, () -> now[0]);
        }

        Kit.TestPlayer player(String name) {
            return Kit.online(name);
        }

        void join(Kit.TestPlayer p) {
            listener.onJoin(new PlayerJoinEvent(p, ""));
        }

        boolean click(Kit.TestPlayer p, int slot) {
            InventoryView view = p.getOpenInventory();
            InventoryClickEvent ev = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            listener.onClick(ev);
            return ev.isCancelled();
        }

        boolean ledgerOpen(Kit.TestPlayer p) {
            return p.opened != null && p.opened.getHolder() instanceof WaitingMenu m && m.size() == WaitingMenu.LEDGER_SIZE;
        }

        boolean pickerOpen(Kit.TestPlayer p) {
            return p.opened != null && p.opened.getHolder() instanceof WaitingMenu m && m.size() == WaitingMenu.PICKER_SIZE;
        }

        /** Runs a /waiting command and returns what the sender was told. */
        List<String> run(Kit.TestPlayer p, String... args) {
            int before = p.messages.size();
            boolean handled = cmd.onCommand(p, null, "waiting", args);
            expect(handled, "cmd: '" + String.join(" ", args) + "' returns true (false would print the usage twice)");
            return new ArrayList<>(p.messages.subList(before, p.messages.size()));
        }

        void put(Kit.TestPlayer p, String key, long inMs) {
            svc.set(p.getUniqueId(), key, "Label " + key, now[0] + inMs);
        }

        void link(Kit.TestPlayer p) {
            Profile pr = store.profile(p.getUniqueId());
            pr.discordId = DISCORD_ID;
            pr.name = p.getName();
            store.markDirty();
        }
    }

    static boolean said(List<String> lines, String fragment) {
        for (String l : lines) {
            if (l.replaceAll("\u00a7.", "").contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    static void fill(Kit.TestPlayer p, int free) {
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            c[i] = i < c.length - free ? new ItemStack(Material.STONE, 64) : null;
        }
    }

    static int count(Kit.TestPlayer p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }

    public static void main(String[] args) throws Exception {
        api();
        popup();
        gating();
        menuContents();
        clicks();
        locks();
        errands();
        telling();
        discordLinking();
        commands();
        pluginWiring();
        hardening();
        shippedConfig();
        Kit.finish();
    }

    // ------------------------------------------------------------------ the API other plugins use

    static void api() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        WaitingApi api = new WaitingApiImpl(e.svc);
        UUID id = UUID.randomUUID();
        api.set(id, "hatchling:1", "Fluffy has grown up", e.now[0] + HOUR);
        expect(api.has(id, "hatchling:1") && !api.has(id, "other") && e.store.get(id, "hatchling:1").label.equals("Fluffy has grown up"), "api: set and has");
        expect(e.store.get(id, "hatchling:1").source.equals("api") && e.store.get(id, "hatchling:1").readyAt == e.now[0] + HOUR, "api: source and time kept");
        expect(e.svc.set(id, "hatchling:1", "Fluffy has grown up", e.now[0] + HOUR), "api: setting the same timer again is fine (true)");
        api.set(id, "hatchling:1", "Fluffy is bigger", e.now[0] + 2 * HOUR);
        expect(e.store.count(id) == 1 && e.store.get(id, "hatchling:1").label.equals("Fluffy is bigger"), "api: same key replaces");
        api.set(id, "blank", "  ", e.now[0]);
        expect(e.store.get(id, "blank").label.equals("blank"), "api: a blank label falls back to the key");
        api.set(id, "nolabel", null, e.now[0]);
        expect(e.store.get(id, "nolabel").label.equals("nolabel"), "api: a null label falls back to the key");
        api.clear(id, "hatchling:1");
        expect(!api.has(id, "hatchling:1"), "api: clear");
        api.clear(id, "hatchling:1");
        api.set(null, "k", "l", 1);
        api.set(id, null, "l", 1);
        api.clear(null, "k");
        api.clear(id, null);
        expect(!api.has(null, "k") && !api.has(id, null) && e.store.count(id) == 2, "api: nulls are ignored, never thrown");
        Env small = new Env(new Kit.MapSection().put("max-timers-per-player", 2));
        WaitingApi sapi = new WaitingApiImpl(small.svc);
        sapi.set(id, "a", "A", 1);
        sapi.set(id, "b", "B", 1);
        sapi.set(id, "c", "C", 1);
        expect(small.store.count(id) == 2 && !sapi.has(id, "c"), "api: over the per-player cap is ignored");
        expect(!small.svc.set(id, "c", "C", 1) && small.svc.set(id, "a", "A2", 1), "service: set says whether it worked");
        expect(small.svc.clear(id, "a") && !small.svc.clear(id, "a") && small.svc.has(id, "b"), "service: clear and has");
    }

    // ------------------------------------------------------------------ the login popup

    static void popup() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer ann = e.player("Ann");
        e.put(ann, "pie", -MIN);
        e.put(ann, "later", HOUR);
        e.join(ann);
        expect(ann.heard("1") && ann.heard("waiting for you"), "join: one thing ready is announced in chat: " + ann.messages);
        expect(e.store.get(ann.getUniqueId(), "pie").notified && !e.store.get(ann.getUniqueId(), "later").notified, "join: ready timers count as told, others do not");
        expect(org.bukkit.scheduler.BukkitScheduler.DELAYS.equals(List.of(160L)), "join: the popup waits 8 seconds (after NexusWelcome's 5): " + org.bukkit.scheduler.BukkitScheduler.DELAYS);
        expect(ann.opened == null, "join: nothing opens before the delay");
        Kit.drain();
        expect(e.ledgerOpen(ann), "join: the list opens after the delay");
        expect(e.store.peekProfile(ann.getUniqueId()).name.equals("Ann"), "join: the profile remembers the name");

        // nothing ready: quiet
        Kit.reset();
        Kit.TestPlayer bob = e.player("Bob");
        e.put(bob, "later", HOUR);
        e.join(bob);
        Kit.drain();
        expect(bob.messages.isEmpty() && bob.opened == null && Kit.queued() == 0, "join: nothing ready, nothing said, nothing scheduled");
        Kit.TestPlayer nobody = e.player("Nobody");
        e.join(nobody);
        expect(nobody.messages.isEmpty() && nobody.opened == null, "join: no timers at all is quiet too");

        // always / never
        Env always = new Env(new Kit.MapSection().put("login.popup", "always"));
        Kit.TestPlayer cy = always.player("Cy");
        always.join(cy);
        Kit.drain();
        expect(always.ledgerOpen(cy) && cy.messages.isEmpty(), "popup always: opens even with nothing ready, no chat line");
        Env never = new Env(new Kit.MapSection().put("login.popup", "never"));
        Kit.TestPlayer di = never.player("Di");
        never.put(di, "x", -1);
        never.join(di);
        Kit.drain();
        expect(di.opened == null && di.heard("waiting for you"), "popup never: no menu, but chat still says it");

        // a player who turned the popup off
        Kit.TestPlayer ed = e.player("Ed");
        e.put(ed, "x", -1);
        e.store.profile(ed.getUniqueId()).popup = false;
        e.join(ed);
        Kit.drain();
        expect(ed.opened == null && ed.heard("waiting for you"), "popup off for the player: chat only");

        // chat off
        Env quiet = new Env(new Kit.MapSection().put("notify-online", false));
        Kit.TestPlayer flo = quiet.player("Flo");
        quiet.put(flo, "x", -1);
        quiet.join(flo);
        Kit.drain();
        expect(flo.messages.isEmpty() && quiet.ledgerOpen(flo), "notify-online off: no chat, popup still opens");

        // zero delay: straight away
        Kit.reset();
        Env now = new Env(new Kit.MapSection().put("login.delay-seconds", 0));
        Kit.TestPlayer gus = now.player("Gus");
        now.put(gus, "x", -1);
        now.join(gus);
        expect(now.ledgerOpen(gus) && Kit.queued() == 0, "delay 0: opens at once");

        // timer cleared between join and popup
        Kit.reset();
        Kit.TestPlayer hal = e.player("Hal");
        e.put(hal, "x", -1);
        e.join(hal);
        e.svc.clear(hal.getUniqueId(), "x");
        Kit.drain();
        expect(hal.opened == null, "popup: nothing left to show when the delay ends");
        // player left before it opened
        Kit.reset();
        Kit.TestPlayer ivy = e.player("Ivy");
        e.put(ivy, "x", -1);
        e.join(ivy);
        ivy.online = false;
        Kit.drain();
        expect(ivy.opened == null, "popup: a player who left gets nothing");
        // popup turned off during the wait
        Kit.reset();
        Kit.TestPlayer jo = e.player("Jo");
        e.put(jo, "x", -1);
        e.join(jo);
        e.store.profile(jo.getUniqueId()).popup = false;
        Kit.drain();
        expect(jo.opened == null, "popup: switched off during the wait is respected");
    }

    static void gating() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        // another menu is open: wait for it, one second at a time
        Kit.TestPlayer ann = e.player("Ann");
        e.put(ann, "x", -1);
        Other o = new Other();
        o.inv = Bukkit.createInventory(o, 27, "chest");
        ann.openInventory(o.inv);
        e.join(ann);
        Kit.runOne();
        expect(ann.opened == o.inv && Kit.queued() == 1, "gate: busy with another menu, tries again");
        expect(org.bukkit.scheduler.BukkitScheduler.DELAYS.get(org.bukkit.scheduler.BukkitScheduler.DELAYS.size() - 1) == 20L, "gate: retries after one second");
        Kit.runOne();
        expect(ann.opened == o.inv && Kit.queued() == 1, "gate: still busy, still trying");
        ann.opened = null;
        Kit.runOne();
        expect(e.ledgerOpen(ann), "gate: opens as soon as the other menu is closed");

        // login screen
        Kit.reset();
        Kit.TestPlayer bob = e.player("Bob");
        e.put(bob, "x", -1);
        e.logging.add(bob.getUniqueId());
        e.join(bob);
        Kit.runOne();
        expect(bob.opened == null && Kit.queued() == 1, "gate: still on the login screen, waits");
        e.logging.clear();
        Kit.runOne();
        expect(e.ledgerOpen(bob), "gate: logged in, opens");

        // wait-for-login off ignores the login screen
        Kit.reset();
        Env nolog = new Env(new Kit.MapSection().put("login.wait-for-login", false));
        Kit.TestPlayer cy = nolog.player("Cy");
        nolog.put(cy, "x", -1);
        nolog.logging.add(cy.getUniqueId());
        nolog.join(cy);
        Kit.drain();
        expect(nolog.ledgerOpen(cy), "gate: wait-for-login off opens regardless");

        // dead
        Kit.reset();
        Kit.TestPlayer di = e.player("Di");
        e.put(di, "x", -1);
        di.dead = true;
        e.join(di);
        Kit.runOne();
        expect(di.opened == null && Kit.queued() == 1, "gate: a dead player waits");
        di.dead = false;
        Kit.runOne();
        expect(e.ledgerOpen(di), "gate: respawned, opens");

        // gives up after the timeout (180 s = 9 retries of 20 ticks past the first try... bounded)
        Kit.reset();
        Env shortWait = new Env(new Kit.MapSection().put("login.timeout-seconds", 5));
        Kit.TestPlayer ed = shortWait.player("Ed");
        shortWait.put(ed, "x", -1);
        shortWait.logging.add(ed.getUniqueId());
        shortWait.join(ed);
        int runs = 0;
        while (Kit.queued() > 0 && runs < 50) {
            Kit.runOne();
            runs++;
        }
        expect(runs == 6 && ed.opened == null, "gate: gives up after the timeout (1 start + 5 retries): " + runs);
        // a click on a non-menu inventory must not drop a menu on someone who has a chest open at the end
        Kit.reset();
        Kit.TestPlayer flo = e.player("Flo");
        e.put(flo, "x", -1);
        Other o2 = new Other();
        o2.inv = Bukkit.createInventory(o2, 27, "chest");
        flo.openInventory(o2.inv);
        expect(!WaitingService.free(flo), "free: a chest open is not free");
        flo.opened = null;
        expect(WaitingService.free(flo), "free: nothing open is free");
    }

    // ------------------------------------------------------------------ what the list looks like

    static String plain(String s) {
        return s.replaceAll("§.", "");
    }

    static void menuContents() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer ann = e.player("Ann");
        e.put(ann, "pie", -5 * MIN);
        e.put(ann, "stew", 90 * MIN);
        e.svc.open(ann);
        Inventory inv = ann.opened;
        WaitingMenu menu = (WaitingMenu) inv.getHolder();
        expect(menu.size() == 45, "menu: five rows");
        expect(plain(Kit.nameOf(inv, WaitingMenu.HEADER)).equals("Waiting for you") && plain(Kit.loreOf(inv, WaitingMenu.HEADER)).contains("1 ready, 1 still"), "menu: header counts: " + Kit.loreOf(inv, 4));
        expect(inv.getItem(10).getType() == Material.EMERALD && plain(Kit.nameOf(inv, 10)).equals("Label pie") && plain(Kit.loreOf(inv, 10)).contains("Ready!"), "menu: a ready timer is an emerald in slot 10");
        expect(inv.getItem(11).getType() == Material.CLOCK && plain(Kit.nameOf(inv, 11)).equals("Label stew") && plain(Kit.loreOf(inv, 11)).contains("1h 30m"), "menu: a waiting timer shows the time left: " + Kit.loreOf(inv, 11));
        expect(menu.at(10).type == WaitingMenu.Type.DISMISS && menu.at(10).arg.equals("pie") && menu.at(11).type == WaitingMenu.Type.NOT_READY && menu.at(11).arg.equals("stew"), "menu: actions match");
        expect(inv.getItem(0).getType() == Material.GRAY_STAINED_GLASS_PANE && menu.at(0) == null, "menu: filler everywhere else, and it does nothing");
        expect(inv.getItem(12).getType() == Material.GRAY_STAINED_GLASS_PANE && menu.at(12) == null, "menu: unused timer slots stay filler");

        // errand row
        expect(inv.getItem(27).getType() == Material.SPYGLASS && menu.at(27).type == WaitingMenu.Type.INFO, "menu: errand label");
        expect(inv.getItem(28).getType() == Material.COMPASS && menu.at(28).type == WaitingMenu.Type.ERRAND_SEND && menu.at(28).n == 1, "menu: one free scout slot");
        expect(inv.getItem(29).getType() == Material.GRAY_STAINED_GLASS_PANE, "menu: only one scout slot with default settings");
        expect(inv.getItem(40).getType() == Material.ENDER_EYE && menu.at(40).type == WaitingMenu.Type.DISCORD && plain(Kit.loreOf(inv, 40)).contains("how to link"), "menu: Discord button when not linked");
        e.link(ann);
        e.svc.open(ann);
        expect(plain(Kit.nameOf(ann.opened, 40)).contains("on") && plain(Kit.loreOf(ann.opened, 40)).contains("turn them off"), "menu: Discord button when linked: " + Kit.nameOf(ann.opened, 40));
        e.store.profile(ann.getUniqueId()).enabled = false;
        e.svc.open(ann);
        expect(plain(Kit.nameOf(ann.opened, 40)).contains("off") && plain(Kit.loreOf(ann.opened, 40)).contains("turn them on"), "menu: Discord button when switched off");
        Env nodiscord = new Env(new Kit.MapSection().put("discord.enabled", false));
        nodiscord.svc.open(ann);
        expect(ann.opened.getItem(40).getType() == Material.GRAY_STAINED_GLASS_PANE, "menu: no Discord button when Discord is not set up");

        // running and finished scouts
        Kit.TestPlayer bob = e.player("Bob");
        e.svc.start(bob, 1, "woods");
        e.svc.open(bob);
        expect(bob.opened.getItem(28).getType() == Material.OAK_SAPLING && plain(Kit.nameOf(bob.opened, 28)).equals("Scout at The Woods")
                && plain(Kit.loreOf(bob.opened, 28)).contains("15m") && ((WaitingMenu) bob.opened.getHolder()).at(28).type == WaitingMenu.Type.ERRAND_RUNNING, "menu: a running scout shows where and for how long: " + Kit.loreOf(bob.opened, 28));
        expect(((WaitingMenu) bob.opened.getHolder()).at(28).n == 1, "menu: scout slot number kept");
        e.now[0] += 15 * MIN;
        e.svc.open(bob);
        expect(bob.opened.getItem(28).getType() == Material.CHEST && plain(Kit.nameOf(bob.opened, 28)).contains("back") && ((WaitingMenu) bob.opened.getHolder()).at(28).type == WaitingMenu.Type.ERRAND_CLAIM,
                "menu: a finished scout is a chest to collect");
        expect(plain(Kit.nameOf(bob.opened, 4)).equals("Waiting for you") && plain(Kit.loreOf(bob.opened, 4)).contains("1 ready, 0 still"), "menu: header counts the scout: " + Kit.loreOf(bob.opened, 4));
        expect(bob.opened.getItem(10).getType() == Material.GRAY_STAINED_GLASS_PANE && bob.opened.getItem(22).getType() == Material.PAPER && plain(Kit.nameOf(bob.opened, 22)).contains("Nothing is waiting"), "menu: scouts do not appear in the timer list, so the empty note shows");
        // a scout whose place was removed from the config
        e.store.put(bob.getUniqueId(), new WaitEntry("errand:1", "x", e.now[0] + HOUR, "errand", "gone"), 99);
        e.svc.open(bob);
        expect(bob.opened.getItem(28).getType() == Material.COMPASS && plain(Kit.nameOf(bob.opened, 28)).contains("gone"), "menu: a scout to a removed place still shows: " + Kit.nameOf(bob.opened, 28));

        // more scout slots
        Kit.TestPlayer vip = e.player("Vip");
        vip.perms.add("nexuswaiting.slots.3");
        e.svc.open(vip);
        expect(vip.opened.getItem(28).getType() == Material.COMPASS && vip.opened.getItem(29).getType() == Material.COMPASS && vip.opened.getItem(30).getType() == Material.COMPASS
                && vip.opened.getItem(31).getType() == Material.GRAY_STAINED_GLASS_PANE, "menu: three scout slots with the permission");

        // errands off
        Env off = new Env(new Kit.MapSection().put("errands.enabled", false));
        off.svc.open(ann);
        expect(ann.opened.getItem(27).getType() == Material.GRAY_STAINED_GLASS_PANE && ann.opened.getItem(28).getType() == Material.GRAY_STAINED_GLASS_PANE, "menu: no scout row when errands are off");

        // overflow
        Kit.TestPlayer cy = e.player("Cy");
        for (int i = 0; i < 14; i++) {
            e.put(cy, "t" + String.format("%02d", i), (i + 1) * MIN);
        }
        e.svc.open(cy);
        int pieces = 0;
        for (int s : WaitingMenu.TIMER_SLOTS) {
            if (cy.opened.getItem(s).getType() == Material.CLOCK) {
                pieces++;
            }
        }
        expect(pieces == 14 && cy.opened.getItem(25).getType() == Material.CLOCK, "menu: exactly 14 timers fit with no overflow marker");
        e.put(cy, "t14", 15 * MIN);
        e.put(cy, "t15", 16 * MIN);
        e.svc.open(cy);
        expect(cy.opened.getItem(25).getType() == Material.PAPER && plain(Kit.nameOf(cy.opened, 25)).equals("+3 more"), "menu: 16 timers: 13 shown and '+3 more': " + Kit.nameOf(cy.opened, 25));
        expect(plain(Kit.nameOf(cy.opened, 24)).equals("Label t12") && plain(Kit.nameOf(cy.opened, 10)).equals("Label t00"), "menu: soonest first, the soonest 13 shown");

        // long labels wrap
        Kit.TestPlayer di = e.player("Di");
        e.svc.set(di.getUniqueId(), "long", "word ".repeat(30).trim(), e.now[0] - 1);
        e.svc.open(di);
        expect(di.opened.getItem(10).getItemMeta().getDisplayName().length() > 70, "menu: item names are not wrapped (only lore is)");
        expect(WaitingMenu.wrap("&7" + "word ".repeat(20).trim(), 38).size() > 2 && WaitingMenu.wrap("&7short", 38).equals(List.of("&7short")) && WaitingMenu.wrap(null, 38).isEmpty(), "menu: wrap splits long lore only");
        for (String piece : WaitingMenu.wrap("&7" + "word ".repeat(20).trim(), 38)) {
            expect(piece.startsWith("&7") && piece.length() <= 40, "menu: every wrapped piece keeps the colour and fits: " + piece);
        }

        // the picker
        WaitingMenu picker = WaitingMenu.picker(e.cfg, 1);
        expect(picker.size() == 27 && plain(Kit.nameOf(picker.getInventory(), 10)).equals("The Woods") && plain(Kit.nameOf(picker.getInventory(), 11)).equals("The Mine")
                && plain(Kit.nameOf(picker.getInventory(), 12)).equals("The Bank"), "picker: places in order from slot 10");
        expect(plain(Kit.loreOf(picker.getInventory(), 10)).contains("a walk") && plain(Kit.loreOf(picker.getInventory(), 10)).contains("Takes 15m") && plain(Kit.loreOf(picker.getInventory(), 10)).contains("Tap to send"), "picker: lore has the story, time and call to action: " + Kit.loreOf(picker.getInventory(), 10));
        expect(picker.at(10).type == WaitingMenu.Type.PICK && picker.at(10).arg.equals("woods") && picker.at(10).n == 1 && picker.at(22).type == WaitingMenu.Type.BACK, "picker: actions");
        expect(WaitingMenu.picker(e.cfg, 3).at(10).n == 3, "picker: remembers which scout slot");
        Env nofill = new Env(new Kit.MapSection().put("menu.filler", false));
        nofill.svc.open(ann);
        expect(ann.opened.getItem(0) == null, "menu: filler can be switched off");
    }

    // ------------------------------------------------------------------ clicking

    static void clicks() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer ann = e.player("Ann");
        e.put(ann, "pie", -MIN);
        e.put(ann, "stew", 30 * MIN);
        e.svc.open(ann);
        expect(e.click(ann, 0) && e.ledgerOpen(ann), "click: filler is cancelled and does nothing");
        expect(e.click(ann, 4) && e.ledgerOpen(ann), "click: header does nothing");
        expect(e.click(ann, 11) && ann.heard("29m") || ann.heard("30m") , "click: a waiting timer says how long: " + ann.messages);
        ann.messages.clear();
        expect(e.click(ann, 10), "click: a ready timer is cancelled like everything");
        expect(e.store.get(ann.getUniqueId(), "pie") == null && ann.heard("Cleared"), "click: a ready timer is cleared");
        expect(e.ledgerOpen(ann) && ann.openCount >= 2 && ann.opened.getItem(10).getType() == Material.CLOCK, "click: the list reopens without it");
        // a ready timer that was pushed back after the menu was built must not be cleared
        e.put(ann, "pie2", -MIN);
        e.svc.open(ann);
        e.put(ann, "pie2", HOUR);
        e.click(ann, 10);
        expect(e.store.get(ann.getUniqueId(), "pie2") != null, "click: a timer that is no longer ready is not cleared by a stale button");
        e.svc.clear(ann.getUniqueId(), "pie2");
        // a timer that vanished
        e.put(ann, "gone", -MIN);
        e.svc.open(ann);
        e.svc.clear(ann.getUniqueId(), "gone");
        ann.messages.clear();
        e.click(ann, 10);
        expect(!ann.heard("Cleared") , "click: a timer that is already gone says nothing");
        e.put(ann, "gone2", HOUR);
        e.svc.open(ann);
        e.svc.clear(ann.getUniqueId(), "gone2");
        ann.messages.clear();
        e.click(ann, 11);
        expect(ann.messages.isEmpty(), "click: a waiting timer that vanished says nothing");

        // errand flow
        e.svc.open(ann);
        e.click(ann, 28);
        expect(e.pickerOpen(ann), "click: an empty scout slot opens the picker");
        e.click(ann, 22);
        expect(e.ledgerOpen(ann), "click: back returns to the list");
        e.click(ann, 28);
        ann.messages.clear();
        e.click(ann, 11); // the mine
        expect(e.ledgerOpen(ann) && e.store.get(ann.getUniqueId(), "errand:1") != null && e.store.get(ann.getUniqueId(), "errand:1").data.equals("mine")
                && ann.heard("The Mine") && ann.heard("1h"), "click: picking a place sends the scout and returns to the list: " + ann.messages);
        expect(e.store.get(ann.getUniqueId(), "errand:1").readyAt == e.now[0] + 60 * MIN && e.store.get(ann.getUniqueId(), "errand:1").label.equals("Your scout is back from The Mine"), "click: ready time and label");
        expect(ann.opened.getItem(28).getType() == Material.IRON_PICKAXE, "click: the list shows the scout out");
        ann.messages.clear();
        e.click(ann, 28);
        expect(ann.heard("1h") && e.ledgerOpen(ann), "click: tapping a running scout says when it is back");
        e.now[0] += 60 * MIN;
        e.svc.open(ann);
        e.click(ann, 28);
        expect(count(ann, Material.COAL) == 2 && count(ann, Material.DIAMOND) == 1 && e.store.get(ann.getUniqueId(), "errand:1") == null && e.ledgerOpen(ann),
                "click: collecting hands over the haul and returns to the list");
        expect(ann.opened.getItem(28).getType() == Material.COMPASS, "click: the scout slot is free again");

        // a picker click for a slot the player does not have
        e.svc.start(ann, 1, "woods");
        e.svc.open(ann);
        ann.messages.clear();
        WaitingMenu pk = WaitingMenu.picker(e.cfg, 1);
        ann.openInventory(pk.getInventory());
        e.click(ann, 10);
        expect(ann.heard("already out") && e.ledgerOpen(ann), "click: all scouts out says so");

        // Discord button
        Kit.TestPlayer bob = e.player("Bob");
        e.svc.open(bob);
        bob.messages.clear();
        e.click(bob, 40);
        expect(bob.opened == null && bob.heard("not linked"), "click: Discord button without a link closes the menu and explains");
        e.link(bob);
        e.svc.open(bob);
        e.click(bob, 40);
        expect(!e.store.profile(bob.getUniqueId()).enabled && bob.heard("off") && e.ledgerOpen(bob), "click: Discord button toggles off and returns");
        e.click(bob, 40);
        expect(e.store.profile(bob.getUniqueId()).enabled && e.ledgerOpen(bob), "click: ...and on");

        // more
        Kit.TestPlayer cy = e.player("Cy");
        for (int i = 0; i < 16; i++) {
            e.put(cy, "t" + i, (i + 1) * MIN);
        }
        e.svc.open(cy);
        cy.messages.clear();
        e.click(cy, 25);
        expect(cy.messages.isEmpty() && e.ledgerOpen(cy), "click: '+N more' does nothing");
        e.click(cy, 27);
        expect(cy.messages.isEmpty(), "click: scouts label does nothing");

        // nobody else's menus
        e.svc.click(ann, WaitingMenu.picker(e.cfg, 1), 5);
        expect(true, "click: a slot with no button is ignored");
    }

    static void locks() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer p = e.player("Ann");
        e.put(p, "x", -1);
        e.svc.open(p);
        InventoryView view = p.getOpenInventory();
        InventoryClickEvent low = new InventoryClickEvent(view, InventoryType.SlotType.QUICKBAR, 10, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        e.listener.onClick(low);
        expect(low.isCancelled() && e.ledgerOpen(p) && p.messages.isEmpty(), "lock: a click in the player's own inventory is cancelled and runs nothing");
        InventoryClickEvent outside = new InventoryClickEvent(p.getOpenInventory(), InventoryType.SlotType.OUTSIDE, -999, ClickType.LEFT, InventoryAction.NOTHING);
        e.listener.onClick(outside);
        expect(outside.isCancelled() && e.store.get(p.getUniqueId(), "x") != null, "lock: outside the window is cancelled");
        InventoryDragEvent drag = new InventoryDragEvent(p.getOpenInventory());
        e.listener.onDrag(drag);
        expect(drag.isCancelled(), "lock: dragging over the menu is cancelled");

        Kit.TestPlayer q = e.player("Bob");
        Other o = new Other();
        o.inv = Bukkit.createInventory(o, 27, "chest");
        q.openInventory(o.inv);
        InventoryClickEvent mine = new InventoryClickEvent(q.getOpenInventory(), InventoryType.SlotType.CONTAINER, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        e.listener.onClick(mine);
        InventoryDragEvent theirs = new InventoryDragEvent(q.getOpenInventory());
        e.listener.onDrag(theirs);
        expect(!mine.isCancelled() && !theirs.isCancelled(), "lock: someone else's chest is left alone");
        InventoryClickEvent none = new InventoryClickEvent(null, InventoryType.SlotType.CONTAINER, 10, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        e.listener.onClick(none);
        expect(!none.isCancelled(), "lock: no view at all is ignored");

        // quit saves
        e.put(p, "y", HOUR);
        e.listener.onQuit(new PlayerQuitEvent(p, ""));
        expect(new File(e.dir, "timers.tsv").exists(), "quit: the data is saved");
    }

    // ------------------------------------------------------------------ errands in detail

    static void errands() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer ann = e.player("Ann");
        UUID id = ann.getUniqueId();
        expect(e.svc.slotsFor(ann) == 1, "errands: one scout by default");
        ann.perms.add("nexuswaiting.slots.2");
        expect(e.svc.slotsFor(ann) == 2, "errands: slots.2 gives two");
        ann.perms.add("nexuswaiting.slots.4");
        expect(e.svc.slotsFor(ann) == 4, "errands: the highest permission wins");
        ann.perms.clear();
        ann.perms.add("nexuswaiting.slots.5");
        expect(e.svc.slotsFor(ann) == 1, "errands: a permission above the maximum does nothing");
        Env roomy = new Env(new Kit.MapSection().put("errands.slots", 3));
        expect(roomy.svc.slotsFor(ann) == 3, "errands: the base can be more than one");
        ann.perms.clear();
        ann.perms.add("nexuswaiting.slots.2");
        expect(roomy.svc.slotsFor(ann) == 3, "errands: a lower permission never reduces the base");

        ann.perms.clear();
        expect(e.svc.freeSlot(ann) == 1, "errands: first free slot");
        expect(e.svc.start(ann, 1, "woods"), "errands: start");
        expect(e.svc.freeSlot(ann) == 0, "errands: none free when the only scout is out");
        ann.messages.clear();
        expect(!e.svc.start(ann, 1, "woods") && ann.heard("already out"), "errands: cannot send the same scout twice");
        expect(!e.svc.start(ann, 2, "woods") && !e.svc.start(ann, 0, "woods") && !e.svc.start(ann, -1, "woods"), "errands: a slot the player does not have");
        expect(!e.svc.start(ann, 1, "nowhere") && !e.svc.start(ann, 1, null), "errands: an unknown place");
        WaitEntry en = e.store.get(id, "errand:1");
        expect(en.isErrand() && en.data.equals("woods") && en.readyAt == e.now[0] + 15 * MIN && en.label.equals("Your scout is back from The Woods") && !en.notified, "errands: the timer");

        // the scout's label comes from the message setting
        Env custom = new Env(new Kit.MapSection().put("messages.errand-label", "Back from {place}!"));
        custom.svc.start(ann, 1, "mine");
        expect(custom.store.get(id, "errand:1").label.equals("Back from The Mine!"), "errands: the label text is configurable, colours stripped from the place");
        Env off = new Env(new Kit.MapSection().put("errands.enabled", false));
        ann.messages.clear();
        expect(!off.svc.start(ann, 1, "woods") && ann.heard("turned off"), "errands: off means off");

        // claim: too early, none, wrong
        ann.messages.clear();
        expect(!e.svc.claim(ann, 1) && ann.heard("15m"), "claim: too early says how long: " + ann.messages);
        expect(e.store.get(id, "errand:1") != null, "claim: too early keeps the scout");
        ann.messages.clear();
        expect(!e.svc.claim(ann, 2) && ann.heard("no scout"), "claim: nothing in that slot");
        e.put(ann, "plain", -1);
        e.store.put(id, new WaitEntry("errand:3", "api thing", 1, "api", ""), 99);
        ann.messages.clear();
        expect(!e.svc.claim(ann, 3) && ann.heard("no scout"), "claim: a timer that is not an errand cannot be claimed");

        // success: items, story, label, scout freed
        e.now[0] += 15 * MIN;
        ann.messages.clear();
        fill(ann, 5);
        expect(e.svc.claim(ann, 1), "claim: ready scout");
        int logs = count(ann, Material.OAK_LOG);
        expect(logs >= 8 && logs <= 24, "claim: two rolls of 4-12 logs: " + logs);
        expect(e.store.get(id, "errand:1") == null && e.svc.freeSlot(ann) == 1, "claim: the scout is free again");
        expect(ann.heard("The Woods") && ann.heard("x Oak Log"), "claim: the message lists the haul: " + ann.messages);
        expect(ann.messages.stream().anyMatch(m -> m.startsWith("§7§o")) , "claim: a story line in grey italics (if the place has stories): " + ann.messages);
        expect(!ann.heard("Lucky"), "claim: no jackpot line without a jackpot");

        // the mine always has a jackpot: coal 2, diamond 1 -> 2 slots
        e.svc.start(ann, 1, "mine");
        e.now[0] += 60 * MIN;
        fill(ann, 1);
        ann.messages.clear();
        expect(!e.svc.claim(ann, 1) && ann.heard("2") && ann.heard("Make room") && e.store.get(id, "errand:1") != null, "claim: not enough room keeps the scout and says how many slots: " + ann.messages);
        expect(count(ann, Material.COAL) == 0 && count(ann, Material.DIAMOND) == 0, "claim: nothing is handed over when it does not fit");
        fill(ann, 0);
        ann.messages.clear();
        expect(!e.svc.claim(ann, 1), "claim: a full inventory");
        fill(ann, 2);
        ann.messages.clear();
        expect(e.svc.claim(ann, 1) && count(ann, Material.COAL) == 2 && count(ann, Material.DIAMOND) == 1, "claim: exactly enough room works");
        expect(ann.heard("Lucky find") && ann.heard("2x Coal") && ann.heard("1x Diamond") && ann.heard("It was dark."), "claim: jackpot line, haul and story: " + ann.messages);

        // commands as console, and no inventory needed
        fill(ann, 0);
        Bukkit.DISPATCHED.clear();
        e.svc.start(ann, 1, "bank");
        e.now[0] += 5 * MIN;
        ann.messages.clear();
        expect(e.svc.claim(ann, 1), "claim: a haul with no items needs no room");
        expect(Bukkit.DISPATCHED.equals(List.of("eco give Ann 5")), "claim: the command runs with the player's name: " + Bukkit.DISPATCHED);
        expect(ann.heard("a reward"), "claim: the message mentions the reward: " + ann.messages);

        // air in the inventory counts as empty
        ItemStack[] c = ann.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            c[i] = new ItemStack(Material.AIR);
        }
        expect(WaitingService.emptySlots(ann) == c.length, "slots: air counts as empty");
        c[0] = new ItemStack(Material.STONE);
        expect(WaitingService.emptySlots(ann) == c.length - 1, "slots: a stone does not");
        expect(WaitingService.pretty(Material.OAK_LOG).equals("Oak Log") && WaitingService.pretty(Material.COAL).equals("Coal"), "pretty: names");

        // the removed place
        e.store.put(id, new WaitEntry("errand:1", "x", e.now[0] - 1, "errand", "removed"), 99);
        ann.messages.clear();
        expect(!e.svc.claim(ann, 1) && e.store.get(id, "errand:1") == null, "claim: a scout to a removed place is released, not stuck");
    }

    // ------------------------------------------------------------------ telling people

    static void telling() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        // online: chat, once
        Kit.TestPlayer ann = e.player("Ann");
        e.svc.set(ann.getUniqueId(), "pie", "Your pie is done", e.now[0] + MIN);
        e.svc.tick();
        expect(ann.messages.isEmpty() && e.posts.isEmpty(), "tick: nothing before it is ready");
        e.now[0] += MIN;
        e.svc.tick();
        expect(ann.heard("Your pie is done") && ann.heard("/waiting") && e.posts.isEmpty(), "tick: an online player hears it in chat, no Discord: " + ann.messages);
        ann.messages.clear();
        e.svc.tick();
        expect(ann.messages.isEmpty(), "tick: only once");
        expect(e.store.get(ann.getUniqueId(), "pie").notified, "tick: marked told");

        // several at once are one line, labels without colour codes
        e.svc.set(ann.getUniqueId(), "a", "&aOne", e.now[0]);
        e.svc.set(ann.getUniqueId(), "b", "Two", e.now[0]);
        e.svc.set(ann.getUniqueId(), "c", "Three", e.now[0]);
        e.svc.set(ann.getUniqueId(), "d", "Four", e.now[0]);
        ann.messages.clear();
        e.svc.tick();
        expect(ann.messages.size() == 1 && ann.heard("One; Two; Three; +1 more") && !ann.heard("&a"), "tick: one chat line for a batch: " + ann.messages);

        // chat off: still told, silently
        Env quiet = new Env(new Kit.MapSection().put("notify-online", false));
        Kit.TestPlayer bo = quiet.player("Bo");
        quiet.svc.set(bo.getUniqueId(), "x", "X", quiet.now[0] - 1);
        quiet.svc.tick();
        expect(bo.messages.isEmpty() && quiet.store.get(bo.getUniqueId(), "x").notified, "tick: notify-online off is silent but counts as told");

        // offline with Discord
        Kit.reset();
        Env d = new Env(null);
        UUID off = UUID.randomUUID();
        Profile pr = d.store.profile(off);
        pr.discordId = DISCORD_ID;
        pr.name = "Cat";
        d.svc.set(off, "hatch", "Fluffy has grown up", d.now[0] + 10 * MIN);
        d.svc.tick();
        expect(d.posts.isEmpty(), "discord: nothing before it is ready");
        d.now[0] += 10 * MIN;
        d.svc.tick();
        expect(d.posts.size() == 1, "discord: one ping when it is ready: " + d.posts);
        expect(d.posts.get(0).equals("{\"content\":\"<@" + DISCORD_ID + "> Something is waiting for you on RealSociety: Fluffy has grown up\",\"username\":\"RealSociety\","
                + "\"allowed_mentions\":{\"parse\":[],\"users\":[\"" + DISCORD_ID + "\"]}}"), "discord: the exact message: " + d.posts.get(0));
        expect(d.store.get(off, "hatch").notified && pr.pingCount == 1 && pr.lastPingAt == d.now[0], "discord: told and counted");
        d.svc.tick();
        expect(d.posts.size() == 1, "discord: not twice");

        // rate limits: min gap, then batching
        d.svc.set(off, "two", "Second", d.now[0]);
        d.svc.tick();
        expect(d.posts.size() == 1 && !d.store.get(off, "two").notified, "discord: too soon after the last ping, held back and still unannounced");
        d.now[0] += 29 * MIN;
        d.svc.tick();
        expect(d.posts.size() == 1, "discord: still too soon");
        d.svc.set(off, "three", "Third", d.now[0]);
        d.now[0] += MIN;
        d.svc.tick();
        expect(d.posts.size() == 2 && d.posts.get(1).contains("Second; Third") && d.store.get(off, "two").notified && d.store.get(off, "three").notified, "discord: held-back ones go out together: " + d.posts.get(1));
        // daily limit of 3
        d.now[0] += 31 * MIN;
        d.svc.set(off, "four", "Fourth", d.now[0]);
        d.svc.tick();
        expect(d.posts.size() == 3, "discord: third of the day");
        d.now[0] += 31 * MIN;
        d.svc.set(off, "five", "Fifth", d.now[0]);
        d.svc.tick();
        expect(d.posts.size() == 3 && !d.store.get(off, "five").notified, "discord: the daily limit holds it back");
        d.now[0] = (PingPolicy.dayOf(d.now[0]) + 1) * PingPolicy.DAY + MIN;
        d.svc.tick();
        expect(d.posts.size() == 4 && d.store.get(off, "five").notified && d.posts.get(3).contains("Fifth"), "discord: a new day, the held-back one goes out: " + d.posts.size());

        // stale things are not worth pinging
        Kit.reset();
        Env s = new Env(null);
        UUID st = UUID.randomUUID();
        s.store.profile(st).discordId = DISCORD_ID;
        s.svc.set(st, "old", "Old news", s.now[0] - 24 * HOUR - 1);
        s.svc.set(st, "fresh", "Fresh", s.now[0] - 24 * HOUR);
        s.svc.tick();
        expect(s.posts.size() == 1 && s.posts.get(0).contains("Fresh") && !s.posts.get(0).contains("Old news"), "stale: older than 24h is skipped, exactly 24h still goes: " + s.posts);
        expect(s.store.get(st, "old").notified, "stale: and marked told so it is not tried again");
        Env onlyStale = new Env(null);
        onlyStale.store.profile(st).discordId = DISCORD_ID;
        onlyStale.svc.set(st, "old", "Old news", onlyStale.now[0] - 48 * HOUR);
        onlyStale.svc.tick();
        expect(onlyStale.posts.isEmpty() && onlyStale.store.get(st, "old").notified, "stale: nothing at all to send");

        // nobody to ping
        UUID nolink = UUID.randomUUID();
        s.svc.set(nolink, "x", "X", s.now[0] - 1);
        s.svc.tick();
        expect(s.store.get(nolink, "x").notified && s.posts.size() == 1, "nobody: not linked, marked told, no post");
        UUID disabled = UUID.randomUUID();
        Profile dp = s.store.profile(disabled);
        dp.discordId = DISCORD_ID;
        dp.enabled = false;
        s.svc.set(disabled, "x", "X", s.now[0] - 1);
        s.svc.tick();
        expect(s.store.get(disabled, "x").notified && s.posts.size() == 1, "nobody: pings switched off by the player");
        Env nohook = new Env(new Kit.MapSection().put("discord.enabled", false));
        nohook.store.profile(st).discordId = DISCORD_ID;
        nohook.svc.set(st, "x", "X", nohook.now[0] - 1);
        nohook.svc.tick();
        expect(nohook.posts.isEmpty() && nohook.store.get(st, "x").notified, "nobody: Discord not set up on the server");
        Env nosender = new Env(null);
        nosender.svc.setWebhook(null);
        nosender.store.profile(st).discordId = DISCORD_ID;
        nosender.svc.set(st, "x", "X", nosender.now[0] - 1);
        nosender.svc.tick();
        expect(nosender.posts.isEmpty() && nosender.store.get(st, "x").notified, "nobody: no sender available");
        UUID noprofile = UUID.randomUUID();
        s.svc.set(noprofile, "x", "X", s.now[0] - 1);
        s.svc.tick();
        expect(s.store.get(noprofile, "x").notified && s.store.peekProfile(noprofile) == null, "nobody: no profile at all, none created");

        // failures come back to be tried again
        Kit.reset();
        Env f = new Env(null);
        f.answer = Boolean.FALSE;
        UUID fid = UUID.randomUUID();
        f.store.profile(fid).discordId = DISCORD_ID;
        f.svc.set(fid, "x", "X", f.now[0] - 1);
        f.svc.tick();
        expect(f.posts.size() == 1 && !f.store.get(fid, "x").notified && f.warns.size() == 1 && f.warns.get(0).contains("did not go through"), "fail: a failed ping is un-told and warned: " + f.warns);
        expect(f.warns.get(0).indexOf("discord.com") < 0, "fail: the warning never contains the address");
        f.svc.tick();
        expect(f.posts.size() == 1, "fail: not retried before the minimum gap");
        f.now[0] += 30 * MIN;
        f.answer = Boolean.TRUE;
        f.svc.tick();
        expect(f.posts.size() == 2 && f.store.get(fid, "x").notified, "fail: retried after the gap, and told when it works");

        // an answer that comes later
        Env later = new Env(null);
        later.answer = null;
        later.store.profile(fid).discordId = DISCORD_ID;
        later.svc.set(fid, "x", "X", later.now[0] - 1);
        later.svc.tick();
        expect(later.pending.size() == 1 && later.store.get(fid, "x").notified && later.warns.isEmpty(), "later: told straight away, answer pending");
        later.pending.get(0).accept(true);
        expect(later.store.get(fid, "x").notified && later.warns.isEmpty(), "later: a late success changes nothing");
        Env late2 = new Env(null);
        late2.answer = null;
        late2.store.profile(fid).discordId = DISCORD_ID;
        late2.svc.set(fid, "x", "X", late2.now[0] - 1);
        late2.svc.tick();
        late2.pending.get(0).accept(false);
        expect(!late2.store.get(fid, "x").notified && late2.warns.size() == 1, "later: a late failure un-tells it");

        // the whole-server limit
        Kit.reset();
        Env sv = new Env(new Kit.MapSection().put("discord.max-pings-per-hour-server", 2));
        for (int i = 0; i < 3; i++) {
            UUID u = UUID.randomUUID();
            sv.store.profile(u).discordId = DISCORD_ID;
            sv.svc.set(u, "x", "X" + i, sv.now[0] - 1);
        }
        sv.svc.tick();
        expect(sv.posts.size() == 2, "server limit: two pings an hour for the whole server: " + sv.posts.size());
        sv.svc.tick();
        expect(sv.posts.size() == 2, "server limit: still held");
        sv.now[0] += HOUR;
        sv.svc.tick();
        expect(sv.posts.size() == 3, "server limit: an hour later the third goes");

        // a label cannot mention anyone
        Env m = new Env(null);
        UUID mid = UUID.randomUUID();
        m.store.profile(mid).discordId = DISCORD_ID;
        m.svc.set(mid, "x", "@everyone <@999> \"quote\" \\ back", m.now[0] - 1);
        m.svc.tick();
        expect(!m.posts.get(0).contains("@everyone") && !m.posts.get(0).contains("<@999>") && m.posts.get(0).contains("\\\"quote\\\" \\\\ back"), "safety: labels cannot mention, and the JSON is escaped: " + m.posts.get(0));
        expect(m.posts.get(0).contains("\"users\":[\"" + DISCORD_ID + "\"]"), "safety: only the linked user may be mentioned");
        Env named = new Env(new Kit.MapSection().put("discord.message", "{player}: {what}"));
        UUID nid = UUID.randomUUID();
        named.store.profile(nid).discordId = DISCORD_ID;
        named.store.profile(nid).name = "Dee";
        named.svc.set(nid, "x", "Thing", named.now[0] - 1);
        named.svc.tick();
        expect(named.posts.get(0).contains("Dee: Thing"), "message: {player} is filled from the profile: " + named.posts.get(0));
    }

    // ------------------------------------------------------------------ linking Discord

    static void discordLinking() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer ann = e.player("Ann");
        UUID id = ann.getUniqueId();
        e.svc.discordStatus(ann);
        expect(ann.heard("not linked"), "link: status before linking");
        ann.messages.clear();
        e.svc.link(ann, "abc");
        expect(ann.heard("not a Discord user ID") && !e.store.profile(id).linked(), "link: a bad id is refused");
        ann.messages.clear();
        e.svc.link(ann, null);
        expect(ann.heard("not a Discord user ID"), "link: null is refused");
        ann.messages.clear();
        e.svc.link(ann, "  " + DISCORD_ID + " ");
        expect(e.store.profile(id).discordId.equals(DISCORD_ID) && e.store.profile(id).enabled && ann.heard("Linked"), "link: a good id is kept (spaces trimmed)");
        ann.messages.clear();
        e.svc.discordStatus(ann);
        expect(ann.heard("linked") && ann.heard("on"), "link: status after");
        ann.messages.clear();
        e.svc.setDiscordEnabled(ann, false);
        expect(!e.store.profile(id).enabled && ann.heard("off"), "link: off");
        ann.messages.clear();
        e.svc.discordStatus(ann);
        expect(ann.heard("off"), "link: status says off");
        e.svc.setDiscordEnabled(ann, true);
        expect(e.store.profile(id).enabled, "link: on");
        expect(e.svc.unlink(id) && !e.store.profile(id).linked() && !e.svc.unlink(id) && !e.svc.unlink(UUID.randomUUID()), "link: unlink once");
        ann.messages.clear();
        e.svc.setDiscordEnabled(ann, true);
        expect(ann.heard("Link first"), "link: cannot switch on without a link");

        // server without Discord
        Env none = new Env(new Kit.MapSection().put("discord.enabled", false));
        ann.messages.clear();
        none.svc.link(ann, DISCORD_ID);
        expect(ann.heard("not set up") && !none.store.profile(id).linked(), "link: refused when the server has no Discord");
        ann.messages.clear();
        none.svc.discordStatus(ann);
        expect(ann.heard("not set up"), "link: status when the server has none");
        ann.messages.clear();
        none.svc.test(ann);
        expect(ann.heard("not set up") && none.posts.isEmpty(), "test: refused when the server has none");

        // test pings
        ann.messages.clear();
        e.svc.test(ann);
        expect(ann.heard("Link first") && e.posts.isEmpty(), "test: needs a link");
        e.svc.link(ann, DISCORD_ID);
        ann.messages.clear();
        e.svc.test(ann);
        expect(e.posts.size() == 1 && e.posts.get(0).contains("test ping from RealSociety") && e.posts.get(0).contains("<@" + DISCORD_ID + ">") && ann.heard("Sent a test"), "test: one test ping: " + e.posts);
        expect(e.store.profile(id).pingCount == 0, "test: does not use up the daily allowance");
        ann.messages.clear();
        e.svc.test(ann);
        expect(e.posts.size() == 1 && ann.heard("Wait a minute"), "test: once a minute");
        e.now[0] += 59_999;
        e.svc.test(ann);
        expect(e.posts.size() == 1, "test: 59.999 seconds is still too soon");
        e.now[0] += 1;
        e.svc.test(ann);
        expect(e.posts.size() == 2, "test: allowed again after a minute");
        e.listener.onQuit(new PlayerQuitEvent(ann, ""));
        e.svc.test(ann);
        expect(e.posts.size() == 3, "test: leaving forgets the cooldown");
        Env bad = new Env(null);
        bad.answer = Boolean.FALSE;
        bad.svc.link(ann, DISCORD_ID);
        bad.svc.test(ann);
        expect(bad.warns.size() == 1 && bad.warns.get(0).contains("Ann"), "test: a failed test is logged for staff: " + bad.warns);

        // popup preference
        ann.messages.clear();
        e.svc.setPopup(ann, false);
        expect(!e.store.profile(id).popup && ann.heard("will not pop up"), "popup: off");
        e.svc.setPopup(ann, true);
        expect(e.store.profile(id).popup && ann.heard("pops up when"), "popup: on");
    }

    // ------------------------------------------------------------------ the command

    static void commands() throws Exception {
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer ann = e.player("Ann");
        Kit.TestPlayer boss = e.player("Boss");
        boss.perms.add(WaitingCommand.ADMIN);
        Kit.TestPlayer cy = e.player("Cy");

        expect(e.cmd.onCommand(ann, null, "waiting", new String[0]) && e.ledgerOpen(ann), "cmd: no arguments opens the list");
        ann.opened = null;
        e.run(ann, "open");
        expect(e.ledgerOpen(ann), "cmd: open");
        // list
        expect(said(e.run(ann, "list"), "Nothing is waiting"), "cmd: list when empty");
        e.put(ann, "later", 90 * MIN);
        e.put(ann, "ready", -1);
        List<String> list = e.run(ann, "list");
        expect(list.size() == 2 && list.get(0).contains("[ready]") && list.get(0).contains("Label ready") && list.get(1).contains("[1h 30m]") && list.get(1).contains("Label later"), "cmd: list is ready first, then by time: " + list);
        e.svc.start(ann, 1, "bank");
        expect(e.run(ann, "list").size() == 3, "cmd: list includes scouts");

        // claim
        e.now[0] += 5 * MIN;
        fill(ann, 0);
        List<String> out = e.run(ann, "claim");
        expect(Bukkit.DISPATCHED.contains("eco give Ann 5") && said(out, "returned"), "cmd: claim with no number takes the first ready scout: " + out);
        expect(said(e.run(ann, "claim"), "no scout"), "cmd: claim with nothing ready");
        e.svc.start(ann, 1, "bank");
        e.now[0] += 5 * MIN;
        Bukkit.DISPATCHED.clear();
        e.run(ann, "claim", "1");
        expect(Bukkit.DISPATCHED.size() == 1, "cmd: claim 1");
        expect(said(e.run(ann, "claim", "x"), "Usage"), "cmd: claim with junk");
        expect(said(e.run(ann, "claim", "2"), "no scout"), "cmd: claim an empty slot");
        Env off = new Env(new Kit.MapSection().put("errands.enabled", false));
        ann.messages.clear();
        expect(off.cmd.onCommand(ann, null, "waiting", new String[]{"claim"}), "cmd: claim when errands are off still counts as handled");
        expect(ann.heard("turned off"), "cmd: claim when errands are off");

        // popup / discord
        expect(said(e.run(ann, "popup", "off"), "will not pop up") && !e.store.profile(ann.getUniqueId()).popup, "cmd: popup off");
        expect(said(e.run(ann, "popup", "ON"), "pops up when") && e.store.profile(ann.getUniqueId()).popup, "cmd: popup on");
        expect(said(e.run(ann, "popup"), "Usage") && said(e.run(ann, "popup", "maybe"), "Usage"), "cmd: popup usage");
        expect(said(e.run(ann, "discord"), "not linked"), "cmd: discord status");
        expect(said(e.run(ann, "discord", "link"), "not a Discord user ID"), "cmd: link needs an id");
        expect(said(e.run(ann, "discord", "link", DISCORD_ID), "Linked"), "cmd: link");
        expect(said(e.run(ann, "discord", "off"), "off") && !e.store.profile(ann.getUniqueId()).enabled, "cmd: discord off");
        expect(said(e.run(ann, "discord", "on"), "on") && e.store.profile(ann.getUniqueId()).enabled, "cmd: discord on");
        expect(said(e.run(ann, "discord", "test"), "Sent a test"), "cmd: discord test");
        expect(said(e.run(ann, "discord", "status"), "linked"), "cmd: discord status word");
        expect(said(e.run(ann, "discord", "unlink"), "unlinked"), "cmd: discord unlink");
        expect(said(e.run(ann, "discord", "unlink"), "Link first"), "cmd: unlink when not linked");

        // staff: set
        expect(said(e.run(ann, "set", "Cy", "k", "5", "x"), "cannot"), "cmd: players cannot set");
        expect(said(e.run(ann, "clear", "Cy", "k"), "cannot") && said(e.run(ann, "test", "Cy"), "cannot") && said(e.run(ann, "unlink", "Cy"), "cannot")
                && said(e.run(ann, "status"), "cannot") && said(e.run(ann, "reload"), "cannot"), "cmd: players cannot use any staff command");
        expect(said(e.run(boss, "set", "Cy"), "Usage"), "cmd: set usage");
        expect(said(e.run(boss, "set", "Cy", "k", "soon", "x"), "Minutes must be") && said(e.run(boss, "set", "Cy", "k", "-1", "x"), "Minutes must be")
                && said(e.run(boss, "set", "Cy", "k", "NaN", "x"), "Minutes must be") && said(e.run(boss, "set", "Cy", "k", "999999", "x"), "Minutes must be"), "cmd: bad minutes");
        Bukkit.NEVER_PLAYED.add("Nobody");
        expect(said(e.run(boss, "set", "Nobody", "k", "5", "x"), "not a player I know"), "cmd: unknown player (never played)");
        List<String> done = e.run(boss, "set", "Cy", "bread", "90", "Your", "bread", "is", "baked");
        WaitEntry w = e.store.get(cy.getUniqueId(), "bread");
        expect(w != null && w.label.equals("Your bread is baked") && w.readyAt == e.now[0] + 90 * MIN && w.source.equals("api") && said(done, "1h 30m"), "cmd: set with a label of several words: " + done);
        e.run(boss, "set", "Cy", "nolabel", "0");
        expect(e.store.get(cy.getUniqueId(), "nolabel").label.equals("nolabel") && e.store.get(cy.getUniqueId(), "nolabel").ready(e.now[0]), "cmd: no label uses the key, 0 minutes is ready now");
        e.run(boss, "set", "Cy", "frac", "0.5", "half");
        expect(e.store.get(cy.getUniqueId(), "frac").readyAt == e.now[0] + 30_000, "cmd: half a minute");
        e.run(boss, "set", "Cy", "year", "525600", "far");
        expect(e.store.get(cy.getUniqueId(), "year") != null, "cmd: a year is allowed");
        Env cap = new Env(new Kit.MapSection().put("max-timers-per-player", 1));
        cap.cmd.onCommand(boss, null, "waiting", new String[]{"set", "Cy", "a", "5"});
        boss.messages.clear();
        cap.cmd.onCommand(boss, null, "waiting", new String[]{"set", "Cy", "b", "5"});
        expect(boss.heard("maximum"), "cmd: too many timers is reported");

        // selectors (command blocks)
        Bukkit.SELECTED.add(cy);
        Bukkit.SELECTED.add(ann);
        Bukkit.SELECTED.add(new org.bukkit.entity.Entity());
        List<String> sel = e.run(boss, "set", "@a", "sel", "10", "Selected");
        expect(e.store.get(cy.getUniqueId(), "sel") != null && e.store.get(ann.getUniqueId(), "sel") != null && said(sel, "2 player"), "cmd: a selector reaches every player it selects, not other entities: " + sel);
        Bukkit.SELECTED.clear();
        expect(said(e.run(boss, "set", "@p", "sel", "10", "x"), "not a player I know"), "cmd: a selector that selects nobody");
        Bukkit.SELECTED.add(cy);
        expect(said(e.run(boss, "clear", "@p", "sel"), "Cleared 1") && e.store.get(cy.getUniqueId(), "sel") == null, "cmd: clear by selector");
        expect(said(e.run(boss, "clear", "Cy", "sel"), "Nothing to clear"), "cmd: clearing nothing");
        expect(said(e.run(boss, "clear", "Cy"), "Usage"), "cmd: clear usage");
        expect(said(e.run(boss, "clear", "Cy", "bread"), "Cleared 1") && e.store.get(cy.getUniqueId(), "bread") == null, "cmd: clear by name");
        expect(said(e.run(boss, "set", "@bogus", "k", "5", "x"), "not a player I know") , "cmd: a bad selector is not an error");

        // test timer
        expect(said(e.run(boss, "test"), "Usage") && said(e.run(boss, "test", "Nobody"), "not a player I know"), "cmd: test usage and unknown");
        cy.messages.clear();
        expect(said(e.run(boss, "test", "Cy"), "Test timer set") && e.store.get(cy.getUniqueId(), "test").ready(e.now[0]), "cmd: test makes a ready timer");
        e.svc.tick();
        expect(cy.heard("Test timer from staff"), "cmd: ...which the next check announces: " + cy.messages);

        // unlink
        e.store.profile(cy.getUniqueId()).discordId = DISCORD_ID;
        expect(said(e.run(boss, "unlink"), "Usage"), "cmd: unlink usage");
        expect(said(e.run(boss, "unlink", "Cy"), "Discord unlinked") && !e.store.profile(cy.getUniqueId()).linked(), "cmd: staff can unlink someone");
        expect(said(e.run(boss, "unlink", "Cy"), "had no Discord"), "cmd: ...once");

        // offline players who have played
        UUID offlineId = Bukkit.getOfflinePlayer("Zed").getUniqueId();
        e.run(boss, "set", "Zed", "k", "5", "x");
        expect(e.store.get(offlineId, "k") != null, "cmd: an offline player who has played can be targeted");

        // status / reload / unknown
        expect(said(e.run(boss, "status"), "timers") && said(e.run(boss, "status"), "Discord pings: ready"), "cmd: status: " + e.run(boss, "status"));
        Env nodisc = new Env(new Kit.MapSection().put("discord.enabled", true).put("discord.webhook-url", "nope"));
        boss.messages.clear();
        nodisc.cmd.onCommand(boss, null, "waiting", new String[]{"status"});
        expect(said(boss.messages, "not usable"), "cmd: status says an enabled but bad address");
        Env offd = new Env(new Kit.MapSection().put("discord.enabled", false).put("errands.enabled", false));
        boss.messages.clear();
        offd.cmd.onCommand(boss, null, "waiting", new String[]{"status"});
        expect(said(boss.messages, "Discord pings: off") && said(boss.messages, "Errands: off"), "cmd: status when both are off: " + boss.messages);
        e.cfg.warnings.add("warn-me");
        List<String> rel = e.run(boss, "reload");
        expect(e.reloads == 1 && said(rel, "reloaded") && said(rel, "warn-me"), "cmd: reload runs and shows warnings: " + rel);
        expect(said(e.run(ann, "bogus"), "Usage") && !said(e.run(ann, "bogus"), "Staff"), "cmd: unknown subcommand shows usage, no staff part for players");
        expect(said(e.run(boss, "bogus"), "Staff"), "cmd: staff see the staff part");
        expect(e.cmd.onCommand(ann, null, "waiting", new String[]{"list"}), "cmd: returns true");

        // tab
        List<String> t1 = e.cmd.onTabComplete(ann, null, "waiting", new String[]{""});
        expect(t1.equals(List.of("open", "list", "claim", "popup", "discord")), "tab: players: " + t1);
        List<String> t2 = e.cmd.onTabComplete(boss, null, "waiting", new String[]{"s"});
        expect(t2.equals(List.of("set", "status")), "tab: staff s: " + t2);
        expect(e.cmd.onTabComplete(boss, null, "waiting", new String[]{"R"}).equals(List.of("reload")), "tab: case-insensitive");
        expect(e.cmd.onTabComplete(ann, null, "waiting", new String[]{"discord", "l"}).equals(List.of("link")), "tab: discord l");
        expect(e.cmd.onTabComplete(ann, null, "waiting", new String[]{"discord", ""}).equals(List.of("link", "unlink", "on", "off", "test", "status")), "tab: discord all");
        expect(e.cmd.onTabComplete(ann, null, "waiting", new String[]{"popup", "o"}).equals(List.of("on", "off")), "tab: popup");
        List<String> t3 = e.cmd.onTabComplete(boss, null, "waiting", new String[]{"set", "c"});
        expect(t3.equals(List.of("Cy")), "tab: staff get names: " + t3);
        expect(e.cmd.onTabComplete(ann, null, "waiting", new String[]{"set", ""}).isEmpty(), "tab: players get no names");
        expect(e.cmd.onTabComplete(boss, null, "waiting", new String[]{"list", ""}).isEmpty(), "tab: list takes no names");
        expect(e.cmd.onTabComplete(boss, null, "waiting", new String[]{"set", "Cy", ""}).isEmpty(), "tab: nothing deeper");
    }

    // ------------------------------------------------------------------ the plugin wires it together

    static void pluginWiring() throws Exception {
        Kit.reset();
        File dir = Files.createTempDirectory("nwait-plugin").toFile();
        org.bukkit.plugin.java.JavaPlugin.DATA_FOLDER = dir;
        WaitingPlugin plugin = new WaitingPlugin();
        plugin.getConfig().set("discord.enabled", true);
        plugin.getConfig().set("discord.webhook-url", HOOK);
        plugin.getConfig().set("check-interval-seconds", 45);
        plugin.onEnable();
        expect(org.bukkit.plugin.PluginManager.REGISTERED.size() == 1 && org.bukkit.plugin.PluginManager.REGISTERED.get(0) instanceof WaitingListener, "plugin: listener registered");
        expect(plugin.getCommand("waiting").getExecutor() instanceof WaitingCommand && plugin.getCommand("waiting").getTabCompleter() instanceof WaitingCommand, "plugin: /waiting has its executor and completer");
        expect(new org.bukkit.Server().getServicesManager().getRegistration(WaitingApi.class) != null
                && new org.bukkit.Server().getServicesManager().getRegistration(WaitingApi.class).getProvider() instanceof WaitingApiImpl, "plugin: the API is registered as a service");
        expect(org.bukkit.scheduler.BukkitScheduler.PERIODS.equals(List.of(900L, 6000L)), "plugin: checks every 45 s and saves every 5 minutes: " + org.bukkit.scheduler.BukkitScheduler.PERIODS);
        org.bukkit.scheduler.BukkitScheduler.REPEATING.get(0).run();
        org.bukkit.scheduler.BukkitScheduler.REPEATING.get(1).run();
        plugin.onDisable();
        expect(true, "plugin: disable does not throw");
        Kit.reset();
        WaitingPlugin plain = new WaitingPlugin();
        plain.onEnable();
        expect(org.bukkit.scheduler.BukkitScheduler.PERIODS.equals(List.of(600L, 6000L)), "plugin: default check interval is 30 s: " + org.bukkit.scheduler.BukkitScheduler.PERIODS);
        plain.onDisable();
    }


    // ------------------------------------------------------------------ gaps the mutation pass found

    /** The Discord sender the plugin's service currently holds (read by reflection). */
    static Object sender(WaitingPlugin plugin) throws Exception {
        java.lang.reflect.Field sf = WaitingPlugin.class.getDeclaredField("service");
        sf.setAccessible(true);
        Object svc = sf.get(plugin);
        java.lang.reflect.Field wf = WaitingService.class.getDeclaredField("webhook");
        wf.setAccessible(true);
        return wf.get(svc);
    }

    /** A fresh store reading the files an Env wrote. */
    static WaitingStore reread(Env e) {
        WaitingStore r = new WaitingStore(new File(e.dir, "timers.tsv"), new File(e.dir, "profiles.tsv"), s -> System.out.println("reread warn: " + s));
        r.load();
        return r;
    }

    static void hardening() throws Exception {
        // ---- everything that changes a profile or a timer reaches the disk
        Kit.reset();
        Env e = new Env(null);
        Kit.TestPlayer ann = e.player("Ann");
        UUID id = ann.getUniqueId();
        e.store.flush();
        e.join(ann);
        e.store.flush();
        expect(reread(e).peekProfile(id) != null && reread(e).peekProfile(id).name.equals("Ann"), "disk: a join saves the player's name");
        e.store.flush();
        e.svc.link(ann, DISCORD_ID);
        e.store.flush();
        Profile saved = reread(e).peekProfile(id);
        expect(saved.discordId.equals(DISCORD_ID) && saved.enabled, "disk: linking is saved");
        e.svc.setDiscordEnabled(ann, false);
        e.store.flush();
        expect(!reread(e).peekProfile(id).enabled, "disk: switching pings off is saved");
        e.svc.setDiscordEnabled(ann, true);
        e.store.flush();
        expect(reread(e).peekProfile(id).enabled, "disk: ...and on");
        e.svc.setPopup(ann, false);
        e.store.flush();
        expect(!reread(e).peekProfile(id).popup, "disk: the popup setting is saved");
        e.svc.setPopup(ann, true);
        e.store.flush();
        expect(reread(e).peekProfile(id).popup, "disk: ...and back on");
        e.svc.unlink(id);
        e.store.flush();
        expect(!reread(e).peekProfile(id).linked(), "disk: unlinking is saved");
        e.svc.link(ann, DISCORD_ID);
        e.store.flush();
        e.cmd.onCommand(ann, null, "waiting", new String[]{"discord", "unlink"});
        e.store.flush();
        expect(!reread(e).peekProfile(id).linked(), "disk: unlinking by command is saved");

        // a ping is counted on disk
        Env d = new Env(null);
        UUID off = UUID.randomUUID();
        d.store.profile(off).discordId = DISCORD_ID;
        d.store.profile(off).name = "Cat";
        d.svc.set(off, "x", "Thing", d.now[0] - 1);
        d.store.flush();
        d.svc.tick();
        d.store.flush();
        Profile counted = reread(d).peekProfile(off);
        expect(counted.pingCount == 1 && counted.lastPingAt == d.now[0] && reread(d).get(off, "x").notified, "disk: a ping and its count are saved");

        // ---- chat text, exactly
        Kit.reset();
        Env c = new Env(null);
        Kit.TestPlayer bo = c.player("Bo");
        for (String k : List.of("a", "b", "c")) {
            c.svc.set(bo.getUniqueId(), k, k.toUpperCase(), c.now[0] - 1);
        }
        c.svc.tick();
        expect(bo.messages.size() == 1 && plain(bo.messages.get(0)).equals("[Waiting] A; B; C Type /waiting to see it."), "chat: exactly three labels, no '+0 more': " + bo.messages);
        Kit.TestPlayer cy = c.player("Cy");
        c.svc.set(cy.getUniqueId(), "solo", "Only one", c.now[0] - 1);
        c.svc.tick();
        expect(plain(cy.messages.get(0)).equals("[Waiting] Only one Type /waiting to see it."), "chat: one label, no leading separator: " + cy.messages);
        UUID offId = UUID.randomUUID();
        c.store.profile(offId).discordId = DISCORD_ID;
        for (String k : List.of("a", "b", "c")) {
            c.svc.set(offId, k, k.toUpperCase(), c.now[0] - 1);
        }
        c.svc.tick();
        expect(c.posts.size() == 1 && c.posts.get(0).contains("on RealSociety: A; B; C\"") && !c.posts.get(0).contains("more"), "discord: exactly three labels, no '+0 more': " + c.posts);

        // ---- popup rules: nothing is even scheduled when it will not open
        Kit.reset();
        Env p = new Env(null);
        Kit.TestPlayer ed = p.player("Ed");
        p.put(ed, "x", -1);
        p.store.profile(ed.getUniqueId()).popup = false;
        p.join(ed);
        expect(Kit.queued() == 0, "popup: a player with it off gets no task scheduled");
        Env never = new Env(new Kit.MapSection().put("login.popup", "never"));
        Kit.TestPlayer di = never.player("Di");
        never.put(di, "x", -1);
        never.join(di);
        expect(Kit.queued() == 0, "popup never: no task scheduled");
        Kit.TestPlayer nv = p.player("Nv");
        nv.nullView = true;
        expect(WaitingService.free(nv), "free: no view at all counts as free");
        nv.nullView = false;
        nv.nullTop = true;
        expect(WaitingService.free(nv), "free: a view without a top inventory counts as free");

        // ---- the menu, at its edges
        Kit.reset();
        Env m = new Env(null);
        Kit.TestPlayer vip = m.player("Vip");
        vip.perms.add("nexuswaiting.slots.4");
        m.svc.open(vip);
        Inventory inv = vip.opened;
        expect(inv.getItem(28).getType() == Material.COMPASS && inv.getItem(29).getType() == Material.COMPASS && inv.getItem(30).getType() == Material.COMPASS
                && inv.getItem(31).getType() == Material.COMPASS && inv.getItem(32).getType() == Material.GRAY_STAINED_GLASS_PANE, "menu: four scout slots with slots.4");
        expect(inv.getItem(0).getItemMeta().getDisplayName().replaceAll("\u00a7.", "").equals(" "), "menu: filler panes are nameless (a space), not 'Gray Stained Glass Pane'");
        expect(inv.getItem(45) == null && inv.getItem(44).getType() == Material.GRAY_STAINED_GLASS_PANE, "menu: the list fills exactly its 45 slots");
        WaitingMenu picker = WaitingMenu.picker(m.cfg, 1);
        expect(picker.getInventory().getItem(27) == null && picker.getInventory().getItem(26).getType() == Material.GRAY_STAINED_GLASS_PANE, "picker: fills exactly its 27 slots");
        expect(plain(Kit.loreOf(picker.getInventory(), 10)).contains("> Tap to send a scout"), "picker: call to action text");
        expect(picker.getInventory().getHolder() == picker && ((WaitingMenu) vip.opened.getHolder()).getInventory() == vip.opened, "menu: holders point back at their inventory");

        // ---- Discord switches with no link, and a server without Discord
        Kit.TestPlayer fy = m.player("Fy");
        fy.messages.clear();
        m.svc.setDiscordEnabled(fy, false);
        expect(m.store.profile(fy.getUniqueId()).enabled && fy.messages.size() == 1 && fy.heard("Link first"), "link: switching off without a link changes nothing and says one thing: " + fy.messages);
        Env none = new Env(new Kit.MapSection().put("discord.enabled", false));
        fy.messages.clear();
        none.svc.test(fy);
        expect(fy.messages.size() == 1 && fy.heard("not set up"), "test: a server without Discord says only that: " + fy.messages);
        Env nosender = new Env(null);
        nosender.svc.setWebhook(null);
        fy.messages.clear();
        nosender.svc.test(fy);
        expect(fy.messages.size() == 1 && fy.heard("not set up") && nosender.posts.isEmpty(), "test: no sender at all behaves the same: " + fy.messages);
        fy.messages.clear();
        m.svc.test(fy);
        expect(fy.messages.size() == 1 && fy.heard("Link first") && m.posts.isEmpty(), "test: not linked says only that and posts nothing: " + fy.messages);

        // ---- the API never lets a null through
        Kit.reset();
        Env a = new Env(null);
        WaitingApi api = new WaitingApiImpl(a.svc);
        UUID who = UUID.randomUUID();
        api.set(null, "k", "l", 1);
        api.set(who, null, "l", 1);
        api.clear(null, "k");
        api.clear(who, null);
        expect(a.store.timerCount() == 0 && !api.has(null, "k") && !api.has(who, null), "api: nulls never create or remove anything");
        api.set(who, "k", "l", 1);
        api.clear(null, "k");
        api.clear(who, null);
        expect(a.store.timerCount() == 1, "api: a null on one side never removes the real timer");
        a.store.put(null, new WaitEntry("x", "x", 1, null, null), 9);
        api.clear(null, "x");
        expect(a.store.get(null, "x") != null, "api: ...even a timer someone put under a null player");

        // ---- from the console and command blocks
        Kit.reset();
        Env k = new Env(null);
        Kit.Console con = new Kit.Console();
        Kit.TestPlayer zed = k.player("Zed");
        expect(k.cmd.onCommand(con, null, "waiting", new String[0]) && con.said("Only players can open the list"), "console: open explains");
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"list"}) && con.out.size() == 2, "console: list explains too");
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"claim"}) && con.said("Only players have scouts"), "console: claim explains");
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"popup", "off"}) && con.said("Only players have a popup"), "console: popup explains");
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"discord", "link", DISCORD_ID}) && con.said("Only players can link"), "console: discord explains");
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"set", "Zed", "oven", "30", "Bread", "baked"}), "console: set returns true");
        expect(k.store.get(zed.getUniqueId(), "oven") != null && k.store.get(zed.getUniqueId(), "oven").readyAt == k.now[0] + 30 * MIN, "console: staff set from the console");
        Bukkit.SELECTED.add(zed);
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"set", "@p", "pie", "0", "Pie", "done"}) && k.store.get(zed.getUniqueId(), "pie") != null, "console: a command block can use @p");
        con.allowed = false;
        con.out.clear();
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"set", "Zed", "x", "1"}) && k.store.get(zed.getUniqueId(), "x") == null && con.said("cannot"), "console: without permission nothing happens");
        con.out.clear();
        expect(k.cmd.onCommand(con, null, "waiting", new String[]{"bogus"}) && !con.said("Staff"), "console: usage for someone without staff rights has no staff part");
        con.allowed = true;
        con.out.clear();
        k.cmd.onCommand(con, null, "waiting", new String[]{"set", "Zed"});
        expect(con.said("/waiting set <player|@p> <key> <minutes> <label...>"), "console: set usage: " + con.out);
        con.out.clear();
        k.cmd.onCommand(con, null, "waiting", new String[]{"bogus"});
        expect(con.said("Usage: /waiting [list|claim|popup on|off|discord link <id>|unlink|on|off|test]") && con.said("Staff: set|clear|test|unlink|status|reload"), "console: full usage text: " + con.out);
        con.out.clear();
        k.cmd.onCommand(con, null, "waiting", new String[]{"set", "Zed", "k", "5"});
        expect(k.store.get(zed.getUniqueId(), "k").label.equals("k"), "set: no label uses the key");
        k.cmd.onCommand(con, null, "waiting", new String[]{"set", "Zed", "k2", "5", "A"});
        expect(k.store.get(zed.getUniqueId(), "k2").label.equals("A"), "set: one word label");

        // ---- a plugin that restarts keeps its data; disabling saves; the autosave cleans old timers
        Kit.reset();
        File dir = Files.createTempDirectory("nwait-plugin2").toFile();
        org.bukkit.plugin.java.JavaPlugin.DATA_FOLDER = dir;
        Files.write(new File(dir, "timers.tsv").toPath(), List.of(UUID.randomUUID() + "\tk\t5\t0\tapi\t\tOn disk"), java.nio.charset.StandardCharsets.UTF_8);
        WaitingPlugin plugin = new WaitingPlugin();
        plugin.getConfig().set("cleanup-days", 1);
        plugin.onEnable();
        Kit.TestPlayer boss = Kit.online("Boss");
        boss.perms.add(WaitingCommand.ADMIN);
        List<String> st = new ArrayList<>();
        boss.messages.clear();
        plugin.getCommand("waiting").getExecutor().onCommand(boss, null, "waiting", new String[]{"status"});
        expect(said(boss.messages, "1 timers"), "plugin: timers on disk are loaded at startup: " + boss.messages);
        WaitingApi live = new org.bukkit.Server().getServicesManager().getRegistration(WaitingApi.class).getProvider();
        UUID u = UUID.randomUUID();
        live.set(u, "old", "Old thing", System.currentTimeMillis() - 5 * PingPolicy.DAY);
        live.set(u, "new", "New thing", System.currentTimeMillis() + PingPolicy.DAY);
        org.bukkit.scheduler.BukkitScheduler.HANDLES.get(1).getClass();
        org.bukkit.scheduler.BukkitScheduler.REPEATING.get(1).run();
        expect(!live.has(u, "old") && live.has(u, "new"), "plugin: the autosave drops timers nobody collected for cleanup-days");
        String onDisk = String.join("\n", Files.readAllLines(new File(dir, "timers.tsv").toPath()));
        expect(onDisk.contains("New thing") && !onDisk.contains("Old thing"), "plugin: ...and saves: " + onDisk);
        live.set(u, "last", "Last thing", System.currentTimeMillis() + PingPolicy.DAY);
        plugin.onDisable();
        expect(String.join("\n", Files.readAllLines(new File(dir, "timers.tsv").toPath())).contains("Last thing"), "plugin: disabling saves what changed");
        expect(org.bukkit.scheduler.BukkitScheduler.HANDLES.get(0).isCancelled() && org.bukkit.scheduler.BukkitScheduler.HANDLES.get(1).isCancelled(), "plugin: disabling stops both timers");

        // reload re-reads the config
        Kit.reset();
        File dir2 = Files.createTempDirectory("nwait-plugin3").toFile();
        org.bukkit.plugin.java.JavaPlugin.DATA_FOLDER = dir2;
        WaitingPlugin p2 = new WaitingPlugin();
        p2.getConfig().set("discord.enabled", true);
        p2.getConfig().set("discord.webhook-url", HOOK);
        p2.onEnable();
        boss.messages.clear();
        p2.getCommand("waiting").getExecutor().onCommand(boss, null, "waiting", new String[]{"status"});
        expect(said(boss.messages, "Discord pings: ready"), "plugin: Discord ready at startup: " + boss.messages);
        expect(sender(p2) instanceof HttpWebhook, "plugin: a real sender is connected when Discord is ready: " + sender(p2));
        p2.getConfig().set("discord.enabled", false);
        boss.messages.clear();
        p2.getCommand("waiting").getExecutor().onCommand(boss, null, "waiting", new String[]{"reload"});
        p2.getCommand("waiting").getExecutor().onCommand(boss, null, "waiting", new String[]{"status"});
        expect(said(boss.messages, "reloaded") && said(boss.messages, "Discord pings: off") && org.bukkit.plugin.java.JavaPlugin.CALLS.contains("reloadConfig"), "plugin: reload picks up the new config: " + boss.messages);
        expect(sender(p2) == null, "plugin: reload with Discord off drops the sender");
        p2.getConfig().set("discord.enabled", true);
        p2.getCommand("waiting").getExecutor().onCommand(boss, null, "waiting", new String[]{"reload"});
        expect(sender(p2) instanceof HttpWebhook, "plugin: reload with Discord on connects the sender");
        p2.getConfig().set("discord.webhook-url", "not a webhook");
        p2.getCommand("waiting").getExecutor().onCommand(boss, null, "waiting", new String[]{"reload"});
        expect(sender(p2) == null, "plugin: reload with a bad address drops the sender");
        p2.onDisable();
    }

    // ------------------------------------------------------------------ the config.yml we ship

    /** Needs -Dshipped.config=<flat dump of config.yml>; skipped (quietly) when it is not given. */
    static void shippedConfig() throws Exception {
        String path = System.getProperty("shipped.config");
        if (path == null) {
            return;
        }
        Kit.MapSection m = new Kit.MapSection();
        for (String line : Files.readAllLines(new File(path).toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", 3);
            if (f.length < 3) {
                continue;
            }
            switch (f[1]) {
                case "b" -> m.put(f[0], Boolean.parseBoolean(f[2]));
                case "i" -> m.put(f[0], Integer.parseInt(f[2]));
                case "l" -> m.put(f[0], new ArrayList<>(List.of(f[2].split("\u0001"))));
                default -> m.put(f[0], f[2]);
            }
        }
        WaitingConfig c = new WaitingConfig();
        c.load(m);
        expect(c.warnings.isEmpty(), "shipped: no warnings: " + c.warnings);
        WaitingConfig d = new WaitingConfig();
        d.load(LogicTest.base());
        expect(c.popup == PopupMode.WHEN_READY && c.delaySeconds == 8 && c.waitForLogin && c.loginTimeoutSeconds == 180 && c.checkIntervalSeconds == 30
                && c.notifyOnline && c.cleanupDays == 30 && c.maxTimersPerPlayer == 40, "shipped: general defaults match the code's");
        expect(!c.discordEnabled && c.minMinutesBetweenPings == 30 && c.maxPingsPerDay == 3 && c.maxPingsPerHour == 30 && c.skipOlderThanHours == 24
                && c.discordUsername.equals(d.discordUsername) && c.serverName.equals(d.serverName) && c.discordMessage.equals(d.discordMessage), "shipped: discord defaults match the code's and it ships switched off");
        expect(c.title.equals(d.title) && c.filler == d.filler && c.fillerIcon == d.fillerIcon, "shipped: menu defaults match");
        expect(c.errandsEnabled && c.baseSlots == 1 && c.maxSlots == 4, "shipped: errands on, one scout");
        List<String> ids = new ArrayList<>();
        for (Destination x : c.destinations) {
            ids.add(x.id);
            expect(x.rewards.size() >= 3 && !x.stories.isEmpty() && x.jackpot.size() >= 1 && x.jackpotPercent > 0 && x.minutes >= 15 && !x.lore.isEmpty(), "shipped: " + x.id + " has rewards, a jackpot, stories, lore");
            for (Reward r : x.rewards) {
                expect(r.item != null && r.max <= 16, "shipped: " + x.id + " rewards are items in modest amounts");
            }
        }
        expect(ids.equals(List.of("woods", "mine", "marsh", "ruins")), "shipped: places in order: " + ids);
        expect(c.destination("woods").minutes == 15 && c.destination("mine").minutes == 60 && c.destination("marsh").minutes == 240 && c.destination("ruins").minutes == 720, "shipped: trip lengths 15m, 1h, 4h, 12h");
        // every message in the file equals the built-in one (so they cannot drift apart)
        for (String key : WaitingConfig.DEFAULT_MESSAGES.keySet()) {
            expect(m.getString("messages." + key) != null && c.msg(key).equals(WaitingConfig.DEFAULT_MESSAGES.get(key)), "shipped: message '" + key + "' matches the built-in text");
        }
        // a full run with it
        Kit.reset();
        Env e = new Env(m);
        Kit.TestPlayer p = e.player("Ann");
        e.svc.start(p, 1, "ruins");
        e.now[0] += 720 * MIN;
        e.join(p);
        Kit.drain();
        expect(e.ledgerOpen(p) && p.opened.getItem(28).getType() == Material.CHEST, "shipped: a returned scout pops the list up at login");
        fill(p, 10);
        e.click(p, 28);
        expect(e.store.get(p.getUniqueId(), "errand:1") == null && p.getInventory().getContents().length > 0, "shipped: the scout's haul can be collected");
    }
}
