package com.nexuscraft.nexuswaiting;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Shared test doubles: players that remember what they were shown, a map-backed config, a task queue. */
final class Kit {

    static int checks;
    static int failures;

    static void expect(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void finish() {
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
    }

    static final class TestPlugin extends JavaPlugin {
    }

    static void reset() {
        Bukkit.PLAYERS.clear();
        Bukkit.ENTITIES.clear();
        Bukkit.DISPATCHED.clear();
        Bukkit.SELECTED.clear();
        Bukkit.NEVER_PLAYED.clear();
        org.bukkit.plugin.PluginManager.ENABLED.clear();
        org.bukkit.plugin.PluginManager.PLUGINS.clear();
        org.bukkit.scheduler.BukkitScheduler.LATER.clear();
        org.bukkit.scheduler.BukkitScheduler.REPEATING.clear();
        org.bukkit.scheduler.BukkitScheduler.HANDLES.clear();
        org.bukkit.scheduler.BukkitScheduler.DELAYS.clear();
        org.bukkit.scheduler.BukkitScheduler.PERIODS.clear();
    }

    /** Runs everything queued with runTaskLater (and anything those tasks queue), in order. */
    static int drain() {
        int ran = 0;
        while (!org.bukkit.scheduler.BukkitScheduler.LATER.isEmpty() && ran < 1000) {
            Runnable r = org.bukkit.scheduler.BukkitScheduler.LATER.remove(0);
            r.run();
            ran++;
        }
        return ran;
    }

    /** Runs just the oldest queued task. */
    static void runOne() {
        if (!org.bukkit.scheduler.BukkitScheduler.LATER.isEmpty()) {
            org.bukkit.scheduler.BukkitScheduler.LATER.remove(0).run();
        }
    }

    static int queued() {
        return org.bukkit.scheduler.BukkitScheduler.LATER.size();
    }

    static final class TestPlayer extends Player {
        final String name;
        final List<String> messages = new ArrayList<>();
        final Set<String> perms = new HashSet<>();
        final List<String> commands = new ArrayList<>();
        boolean played = true;
        boolean nullView;
        boolean nullTop;
        boolean online = true;
        boolean dead;
        Inventory opened;
        int openCount;
        /** Called after every close (stands in for the server firing InventoryCloseEvent). */
        Consumer<Inventory> onClose;
        /** What a command does on the "server" (e.g. opens a menu). */
        Consumer<String> onCommand;

        TestPlayer(String name) {
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public boolean isOnline() { return online; }
        @Override public boolean isDead() { return dead; }
        @Override public boolean hasPlayedBefore() { return played; }
        @Override public boolean hasPermission(String node) { return perms.contains(node); }
        @Override public void sendMessage(String m) { messages.add(m); }

        @Override
        public void openInventory(Inventory inventory) {
            opened = inventory;
            openCount++;
        }

        @Override
        public void closeInventory() {
            if (opened != null) {
                Inventory was = opened;
                opened = null;
                if (onClose != null) {
                    onClose.accept(was);
                }
            }
        }

        @Override
        public InventoryView getOpenInventory() {
            if (nullView) {
                return null;
            }
            InventoryView v = new InventoryView();
            Inventory top = opened;
            if (top == null) {
                top = new Inventory();
                top.setStubType(InventoryType.CRAFTING);
            }
            v.setTopInventory(nullTop ? null : top);
            v.setBottomInventory(new Inventory());
            v.setPlayer(this);
            return v;
        }

        @Override
        public boolean performCommand(String command) {
            commands.add(command);
            if (onCommand != null) {
                onCommand.accept(command);
            }
            return true;
        }

        boolean heard(String fragment) {
            for (String m : messages) {
                if (m.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A command sender that is not a player (the console, a command block). */
    static final class Console implements org.bukkit.command.CommandSender {
        final List<String> out = new ArrayList<>();
        boolean allowed = true;

        @Override public boolean hasPermission(String node) { return allowed; }
        @Override public void sendMessage(String message) { out.add(message); }
        @Override public void sendMessage(net.kyori.adventure.text.Component message) { }
        @Override public String getName() { return "CONSOLE"; }

        boolean said(String fragment) {
            for (String m : out) {
                if (m.replaceAll("\u00a7.", "").contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    static TestPlayer online(String name) {
        TestPlayer p = new TestPlayer(name);
        Bukkit.PLAYERS.put(p.getUniqueId(), p);
        Bukkit.ENTITIES.put(p.getUniqueId(), p);
        return p;
    }

    /** Name of an item, colour codes and all ("" for nothing). */
    static String nameOf(Inventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        return it == null ? "" : it.getItemMeta().getDisplayName();
    }

    static String loreOf(Inventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        return it == null || it.getItemMeta().getLore() == null ? "" : String.join("\n", it.getItemMeta().getLore());
    }

    /** A map-backed ConfigurationSection so config parsing can be tested with real values. */
    static final class MapSection implements ConfigurationSection {
        final Map<String, Object> values = new java.util.LinkedHashMap<>();

        MapSection put(String path, Object v) {
            values.put(path, v);
            return this;
        }

        @Override public boolean getBoolean(String path) { return getBoolean(path, false); }
        @Override public boolean getBoolean(String path, boolean def) { return values.get(path) instanceof Boolean b ? b : def; }
        @Override public int getInt(String path) { return getInt(path, 0); }
        @Override public int getInt(String path, int def) { return values.get(path) instanceof Number n ? n.intValue() : def; }
        @Override public long getLong(String path) { return getLong(path, 0L); }
        @Override public long getLong(String path, long def) { return values.get(path) instanceof Number n ? n.longValue() : def; }
        @Override public double getDouble(String path) { return getDouble(path, 0.0); }
        @Override public double getDouble(String path, double def) { return values.get(path) instanceof Number n ? n.doubleValue() : def; }
        @Override public String getString(String path) { return getString(path, null); }
        @Override public String getString(String path, String def) { return values.get(path) instanceof String s ? s : def; }
        @Override @SuppressWarnings("unchecked") public List<String> getStringList(String path) {
            return values.get(path) instanceof List<?> l ? (List<String>) l : new ArrayList<>();
        }
        @Override @SuppressWarnings("unchecked") public List<Integer> getIntegerList(String path) {
            return values.get(path) instanceof List<?> l ? (List<Integer>) l : new ArrayList<>();
        }
        @Override public List<Double> getDoubleList(String path) { return new ArrayList<>(); }
        @Override public List<?> getList(String path) { return new ArrayList<>(); }
        @Override public List<Map<?, ?>> getMapList(String path) { return new ArrayList<>(); }
        @Override public ItemStack getItemStack(String path) { return null; }
        @Override public ItemStack getItemStack(String path, ItemStack def) { return def; }
        @Override public ConfigurationSection getConfigurationSection(String path) {
            MapSection sub = new MapSection();
            String prefix = path + ".";
            boolean any = false;
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    sub.values.put(e.getKey().substring(prefix.length()), e.getValue());
                    any = true;
                }
            }
            return any ? sub : null;
        }
        @Override public ConfigurationSection createSection(String path) { return new MapSection(); }
        @Override public void set(String path, Object value) { values.put(path, value); }
        @Override public Set<String> getKeys(boolean deep) {
            Set<String> keys = new LinkedHashSet<>();
            for (String k : values.keySet()) {
                keys.add(deep || !k.contains(".") ? k : k.substring(0, k.indexOf('.')));
            }
            return keys;
        }
        @Override public boolean isSet(String path) { return values.containsKey(path); }
        @Override public boolean contains(String path) { return values.containsKey(path); }
    }
}
