package com.nexuscraft.nexuswaiting;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The "waiting for you" list as a plain chest menu, so Java players and Bedrock/Xbox players (Geyser shows chest
 * menus as a tap-to-select screen) get the same thing. Nothing in it can be taken out.
 */
final class WaitingMenu implements InventoryHolder {

    enum Type { INFO, DISMISS, NOT_READY, ERRAND_SEND, ERRAND_CLAIM, ERRAND_RUNNING, PICK, BACK, DISCORD }

    /** What a button does when tapped. */
    static final class Action {
        final Type type;
        final String arg;
        final int n;

        Action(Type type, String arg, int n) {
            this.type = type;
            this.arg = arg;
            this.n = n;
        }
    }

    static final int LEDGER_SIZE = 45;
    static final int PICKER_SIZE = 27;
    static final int HEADER = 4;
    static final int[] TIMER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    static final int ERRAND_LABEL = 27;
    static final int ERRAND_FIRST = 28;
    static final int DISCORD_SLOT = 40;
    static final int EMPTY_NOTE = 22;
    static final int PICKER_FIRST = 10;
    static final int PICKER_BACK = 22;
    /** Visible characters per lore line before it is wrapped (phones and consoles cut long lines off). */
    static final int WRAP = 38;

    private final Map<Integer, Action> bySlot = new HashMap<>();
    private final int size;
    private Inventory inventory;

    private WaitingMenu(int size) {
        this.size = size;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    int size() {
        return size;
    }

    Action at(int slot) {
        return bySlot.get(slot);
    }

    // ------------------------------------------------------------------ the list

    static WaitingMenu ledger(WaitingConfig cfg, WaitingStore store, UUID id, long now, int errandSlots) {
        WaitingMenu m = new WaitingMenu(LEDGER_SIZE);
        m.start(cfg);
        List<WaitEntry> all = store.all(id);
        int ready = 0;
        for (WaitEntry e : all) {
            if (e.ready(now)) {
                ready++;
            }
        }
        m.put(HEADER, Material.CLOCK, "&d&lWaiting for you", List.of(
                "&f" + ready + "&7 ready, &f" + (all.size() - ready) + "&7 still on their way."), new Action(Type.INFO, "", 0));

        List<WaitEntry> shown = store.visible(id, now);
        int room = TIMER_SLOTS.length;
        boolean overflow = shown.size() > room;
        int count = overflow ? room - 1 : shown.size();
        for (int i = 0; i < count; i++) {
            WaitEntry e = shown.get(i);
            if (e.ready(now)) {
                m.put(TIMER_SLOTS[i], Material.EMERALD, "&a" + e.label,
                        List.of("&aReady!", "&7Tap to clear it from your list."), new Action(Type.DISMISS, e.key, 0));
            } else {
                m.put(TIMER_SLOTS[i], Material.CLOCK, "&e" + e.label,
                        List.of("&7Ready in &f" + TimeText.left(e.readyAt - now)), new Action(Type.NOT_READY, e.key, 0));
            }
        }
        if (overflow) {
            m.put(TIMER_SLOTS[room - 1], Material.PAPER, "&7+" + (shown.size() - count) + " more",
                    List.of("&7The soonest ones are shown first."), new Action(Type.INFO, "", 0));
        }

        if (cfg.errandsEnabled) {
            m.put(ERRAND_LABEL, Material.SPYGLASS, "&b&lScouts",
                    List.of("&7Send a scout out. It comes back with a haul and a story."), new Action(Type.INFO, "", 0));
            for (int slot = 1; slot <= errandSlots && slot <= 4; slot++) {
                m.errandButton(cfg, store.get(id, "errand:" + slot), slot, now);
            }
        }
        if (shown.isEmpty()) {
            m.put(EMPTY_NOTE, Material.PAPER, "&7Nothing is waiting for you.",
                    List.of("&7Things that take real time, like a growing pet or a scout, show up here."), new Action(Type.INFO, "", 0));
        }
        if (cfg.discordReady()) {
            m.discordButton(store.peekProfile(id));
        }
        return m;
    }

    private void discordButton(Profile profile) {
        if (profile != null && profile.linked()) {
            put(DISCORD_SLOT, Material.ENDER_EYE, "&9Discord pings: " + (profile.enabled ? "&aon" : "&coff"),
                    List.of("&7Tap to turn them " + (profile.enabled ? "off" : "on") + ".",
                            "&7You hear about ready things even when you are offline."), new Action(Type.DISCORD, "", 0));
        } else {
            put(DISCORD_SLOT, Material.ENDER_EYE, "&9Discord pings",
                    List.of("&7Get a ping when something is ready", "&7and you are not online.",
                            "&7Tap here to see how to link."), new Action(Type.DISCORD, "", 0));
        }
    }

    private void errandButton(WaitingConfig cfg, WaitEntry e, int slot, long now) {
        int at = ERRAND_FIRST + slot - 1;
        if (e == null) {
            put(at, Material.COMPASS, "&bSend a scout", List.of("&7Tap to choose where."), new Action(Type.ERRAND_SEND, "", slot));
            return;
        }
        Destination d = cfg.destination(e.data);
        String place = d == null ? e.data : d.name;
        if (e.ready(now)) {
            put(at, Material.CHEST, "&aScout is back!", List.of("&7From " + place, "&aTap to collect."), new Action(Type.ERRAND_CLAIM, "", slot));
        } else {
            put(at, d == null ? Material.COMPASS : d.icon, "&eScout at " + place,
                    List.of("&7Back in &f" + TimeText.left(e.readyAt - now)), new Action(Type.ERRAND_RUNNING, "", slot));
        }
    }

    // ------------------------------------------------------------------ choosing where to send a scout

    static WaitingMenu picker(WaitingConfig cfg, int errandSlot) {
        WaitingMenu m = new WaitingMenu(PICKER_SIZE);
        m.start(cfg);
        int slot = PICKER_FIRST;
        for (Destination d : cfg.destinations) {
            List<String> lore = new ArrayList<>(d.lore);
            lore.add("&7Takes &f" + TimeText.left(d.millis()));
            lore.add("&e> Tap to send a scout");
            m.put(slot++, d.icon, d.name, lore, new Action(Type.PICK, d.id, errandSlot));
        }
        m.put(PICKER_BACK, Material.ARROW, "&7Back", List.of(), new Action(Type.BACK, "", 0));
        return m;
    }

    // ------------------------------------------------------------------ building blocks

    private void start(WaitingConfig cfg) {
        inventory = Bukkit.createInventory(this, size, ColorText.translate(cfg.title));
        if (cfg.filler) {
            ItemStack pane = new ItemStack(cfg.fillerIcon);
            ItemMeta meta = pane.getItemMeta();
            meta.setDisplayName(ColorText.translate("&r "));
            pane.setItemMeta(meta);
            for (int i = 0; i < size; i++) {
                inventory.setItem(i, pane);
            }
        }
    }

    private void put(int slot, Material icon, String name, List<String> lore, Action action) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ColorText.translate(name));
        List<String> out = new ArrayList<>();
        for (String line : lore) {
            for (String piece : wrap(line, WRAP)) {
                out.add(ColorText.translate(piece));
            }
        }
        meta.setLore(out);
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
        bySlot.put(slot, action);
    }

    /** Splits a long line at spaces; every piece keeps the line's leading colour codes. */
    static List<String> wrap(String line, int width) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        int start = 0;
        while (start + 1 < line.length() && line.charAt(start) == '&') {
            start += 2;
        }
        String codes = line.substring(0, start);
        String rest = line.substring(start);
        if (rest.length() <= width) {
            out.add(line);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (String word : rest.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > width) {
                out.add(codes + cur);
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(word);
        }
        if (cur.length() > 0) {
            out.add(codes + cur);
        }
        return out;
    }
}
