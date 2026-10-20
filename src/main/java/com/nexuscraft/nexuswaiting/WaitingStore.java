package com.nexuscraft.nexuswaiting;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Every player's timers and Discord profile, kept in two tab-separated files (written to a temp file first). */
final class WaitingStore {

    /** A timer together with whose it is. */
    static final class Due {
        final UUID player;
        final WaitEntry entry;

        Due(UUID player, WaitEntry entry) {
            this.player = player;
            this.entry = entry;
        }
    }

    private final File timersFile;
    private final File profilesFile;
    private final Consumer<String> warn;
    private final Map<UUID, Map<String, WaitEntry>> timers = new HashMap<>();
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private boolean dirty;

    WaitingStore(File timersFile, File profilesFile, Consumer<String> warn) {
        this.timersFile = timersFile;
        this.profilesFile = profilesFile;
        this.warn = warn;
    }

    // ------------------------------------------------------------------ loading

    void load() {
        timers.clear();
        profiles.clear();
        dirty = false;
        int bad = readLines(timersFile, this::parseTimer) + readLines(profilesFile, this::parseProfile);
        if (bad > 0) {
            warn.accept("Skipped " + bad + " unreadable line(s) in the NexusWaiting data files.");
        }
    }

    private int readLines(File file, java.util.function.Predicate<String> parser) {
        if (!file.exists()) {
            return 0;
        }
        int bad = 0;
        try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (!parser.test(line)) {
                    bad++;
                }
            }
        } catch (IOException ex) {
            warn.accept("Could not read " + file.getName() + ": " + ex.getMessage());
        }
        return bad;
    }

    private boolean parseTimer(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 7) {
            return false;
        }
        try {
            UUID id = UUID.fromString(f[0]);
            WaitEntry e = new WaitEntry(f[1], f[6], Long.parseLong(f[2]), f[4], f[5]);
            if (e.key.isEmpty()) {
                return false;
            }
            e.notified = "1".equals(f[3]);
            timers.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(e.key, e);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean parseProfile(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 8) {
            return false;
        }
        try {
            UUID id = UUID.fromString(f[0]);
            Profile p = new Profile();
            p.discordId = DiscordMessage.validUserId(f[1]) ? f[1] : "";
            p.enabled = !"0".equals(f[2]);
            p.lastPingAt = Long.parseLong(f[3]);
            p.pingDay = Long.parseLong(f[4]);
            p.pingCount = Math.max(0, Integer.parseInt(f[5]));
            p.popup = !"0".equals(f[6]);
            p.name = WaitEntry.flat(f[7]).trim();
            profiles.put(id, p);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    // ------------------------------------------------------------------ timers

    /**
     * Adds a timer or replaces the one with the same key. Replacing with the same ready time keeps "already told"
     * (a plugin that re-registers its timers at every restart must not cause a second ping).
     * Returns false when the player is at {@code maxPerPlayer} timers and this key is new.
     */
    boolean put(UUID id, WaitEntry fresh, int maxPerPlayer) {
        if (fresh.key.isEmpty()) {
            return false;
        }
        Map<String, WaitEntry> mine = timers.computeIfAbsent(id, k -> new LinkedHashMap<>());
        WaitEntry old = mine.get(fresh.key);
        if (old == null && mine.size() >= maxPerPlayer) {
            return false;
        }
        if (old != null && old.readyAt == fresh.readyAt) {
            fresh.notified = old.notified;
            if (old.label.equals(fresh.label) && old.source.equals(fresh.source) && old.data.equals(fresh.data)) {
                return true; // exactly what is already there: nothing to save
            }
        }
        mine.put(fresh.key, fresh);
        dirty = true;
        return true;
    }

    boolean remove(UUID id, String key) {
        Map<String, WaitEntry> mine = timers.get(id);
        if (mine == null) {
            return false;
        }
        boolean had = mine.remove(WaitEntry.cleanKey(key)) != null;
        if (had) {
            dirty = true;
            if (mine.isEmpty()) {
                timers.remove(id);
            }
        }
        return had;
    }

    WaitEntry get(UUID id, String key) {
        Map<String, WaitEntry> mine = timers.get(id);
        return mine == null ? null : mine.get(WaitEntry.cleanKey(key));
    }

    int count(UUID id) {
        Map<String, WaitEntry> mine = timers.get(id);
        return mine == null ? 0 : mine.size();
    }

    /** Everything this player waits for, errands included, ready ones first then soonest first. */
    List<WaitEntry> all(UUID id) {
        Map<String, WaitEntry> mine = timers.get(id);
        return mine == null ? new ArrayList<>() : new ArrayList<>(mine.values());
    }

    /** The timers shown in the list (errands have their own row), ready ones first, then soonest first. */
    List<WaitEntry> visible(UUID id, long now) {
        List<WaitEntry> out = new ArrayList<>();
        for (WaitEntry e : all(id)) {
            if (!e.isErrand()) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt((WaitEntry e) -> e.ready(now) ? 0 : 1)
                .thenComparingLong(e -> e.readyAt).thenComparing(e -> e.key));
        return out;
    }

    /** How many of this player's timers (errands too) are ready right now. */
    int readyCount(UUID id, long now) {
        int n = 0;
        for (WaitEntry e : all(id)) {
            if (e.ready(now)) {
                n++;
            }
        }
        return n;
    }

    /** Ready timers nobody has been told about yet, oldest first. */
    List<Due> due(long now) {
        List<Due> out = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, WaitEntry>> p : timers.entrySet()) {
            for (WaitEntry e : p.getValue().values()) {
                if (e.ready(now) && !e.notified) {
                    out.add(new Due(p.getKey(), e));
                }
            }
        }
        out.sort(Comparator.comparingLong((Due d) -> d.entry.readyAt).thenComparing(d -> d.entry.key));
        return out;
    }

    void setNotified(UUID id, String key, boolean told) {
        WaitEntry e = get(id, key);
        if (e != null && e.notified != told) {
            e.notified = told;
            dirty = true;
        }
    }

    /** Drops finished timers nobody collected for {@code maxAgeMs}; errands are never dropped (they hold a haul). */
    int cleanup(long now, long maxAgeMs) {
        int removed = 0;
        for (var it = timers.entrySet().iterator(); it.hasNext(); ) {
            Map<String, WaitEntry> mine = it.next().getValue();
            int before = mine.size();
            mine.values().removeIf(e -> !e.isErrand() && now - e.readyAt > maxAgeMs);
            removed += before - mine.size();
            if (mine.isEmpty()) {
                it.remove();
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    int timerCount() {
        int n = 0;
        for (Map<String, WaitEntry> m : timers.values()) {
            n += m.size();
        }
        return n;
    }

    // ------------------------------------------------------------------ profiles

    /** The player's profile, created blank when there is none. Changing it? call {@link #markDirty()}. */
    Profile profile(UUID id) {
        return profiles.computeIfAbsent(id, k -> new Profile());
    }

    Profile peekProfile(UUID id) {
        return profiles.get(id);
    }

    int linkedCount() {
        int n = 0;
        for (Profile p : profiles.values()) {
            if (p.linked()) {
                n++;
            }
        }
        return n;
    }

    void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ saving

    void flush() {
        if (!dirty) {
            return;
        }
        List<String> t = new ArrayList<>();
        t.add("# NexusWaiting timers: uuid, key, readyAt, notified, source, data, label");
        for (Map.Entry<UUID, Map<String, WaitEntry>> p : timers.entrySet()) {
            for (WaitEntry e : p.getValue().values()) {
                t.add(p.getKey() + "\t" + e.key + "\t" + e.readyAt + "\t" + (e.notified ? "1" : "0") + "\t" + e.source
                        + "\t" + e.data + "\t" + e.label);
            }
        }
        List<String> pr = new ArrayList<>();
        pr.add("# NexusWaiting profiles: uuid, discordId, enabled, lastPingAt, pingDay, pingCount, popup, name");
        for (Map.Entry<UUID, Profile> e : profiles.entrySet()) {
            Profile p = e.getValue();
            pr.add(e.getKey() + "\t" + p.discordId + "\t" + (p.enabled ? "1" : "0") + "\t" + p.lastPingAt + "\t" + p.pingDay
                    + "\t" + p.pingCount + "\t" + (p.popup ? "1" : "0") + "\t" + WaitEntry.flat(p.name));
        }
        if (write(timersFile, t) & write(profilesFile, pr)) {
            dirty = false;
        }
    }

    private boolean write(File file, List<String> lines) {
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            warn.accept("Could not save " + file.getName() + ": " + ex.getMessage());
            return false;
        }
    }
}
