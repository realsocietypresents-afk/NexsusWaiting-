package com.nexuscraft.nexuswaiting;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.nexuscraft.nexuswaiting.Kit.expect;

/** The pure parts: times, the ledger file, ping limits, the Discord message, errand rolls, config checking. */
public class LogicTest {

    static final long MIN = 60_000L;
    static final long HOUR = 60 * MIN;
    static final long DAY = 24 * HOUR;
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    static class FakeGate extends JavaPlugin {
        final java.util.Set<UUID> ids = new java.util.HashSet<>();
        @SuppressWarnings("unused")
        private final Object pendingAuthManager = com.nexuscraft.fakegate.Hidden.create(ids);
        boolean enabled = true;

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }

    static class NoFieldGate extends JavaPlugin {
    }

    static class NullManagerGate extends JavaPlugin {
        @SuppressWarnings("unused")
        private final Object pendingAuthManager = null;
    }

    public static void main(String[] args) throws Exception {
        timeText();
        entries();
        pingPolicy();
        discordMessage();
        store();
        storeFiles();
        storeFilesHard();
        rolls();
        popupMode();
        config();
        webhook();
        probe();
        hardening();
        Kit.finish();
    }

    // ------------------------------------------------------------------ time

    static void timeText() {
        expect(TimeText.left(0).equals("ready") && TimeText.left(-5).equals("ready"), "time: zero and negative are ready");
        expect(TimeText.left(1).equals("1m"), "time: one millisecond left is 1m, never 0m: " + TimeText.left(1));
        expect(TimeText.left(59_999).equals("1m") && TimeText.left(60_000).equals("1m") && TimeText.left(60_001).equals("2m"), "time: rounds up at the minute");
        expect(TimeText.left(45 * MIN).equals("45m"), "time: 45m");
        expect(TimeText.left(HOUR).equals("1h"), "time: whole hour has no minutes: " + TimeText.left(HOUR));
        expect(TimeText.left(HOUR + 5 * MIN).equals("1h 5m"), "time: 1h 5m: " + TimeText.left(HOUR + 5 * MIN));
        expect(TimeText.left(23 * HOUR + 59 * MIN).equals("23h 59m"), "time: 23h 59m");
        expect(TimeText.left(DAY).equals("1d"), "time: whole day: " + TimeText.left(DAY));
        expect(TimeText.left(DAY + 3 * HOUR + 20 * MIN).equals("1d 3h"), "time: days drop minutes: " + TimeText.left(DAY + 3 * HOUR + 20 * MIN));
        expect(TimeText.left(2 * DAY + 5 * MIN).equals("2d 1h") || TimeText.left(2 * DAY + 5 * MIN).equals("2d"), "time: 2d 5m rounds to " + TimeText.left(2 * DAY + 5 * MIN));
        expect(TimeText.left(2 * DAY - 1).equals("2d"), "time: just under two days rounds up to 2d: " + TimeText.left(2 * DAY - 1));
    }

    // ------------------------------------------------------------------ entries

    static void entries() {
        WaitEntry e = new WaitEntry("  my key\twith\nstuff ", "Fluffy\thas\ngrown §aup", 500, null, null);
        expect(e.key.equals("my_key_with_stuff"), "entry: key whitespace becomes underscores: '" + e.key + "'");
        expect(!e.label.contains("\t") && !e.label.contains("\n") && !e.label.contains("§"), "entry: label has no tab, newline or section sign: '" + e.label + "'");
        expect(e.label.equals("Fluffy has grown &aup"), "entry: section sign becomes &: '" + e.label + "'");
        expect(e.source.equals(WaitEntry.SOURCE_API) && e.data.isEmpty() && !e.notified && !e.isErrand(), "entry: defaults");
        expect(new WaitEntry("k", "l", 1, " ", "x").source.equals("api"), "entry: blank source is api");
        expect(new WaitEntry("k", "l", 1, "errand", "woods").isErrand(), "entry: errand source");
        expect(new WaitEntry("a".repeat(100), "l", 1, null, null).key.length() == WaitEntry.MAX_KEY, "entry: key capped");
        expect(new WaitEntry("k", "x".repeat(200), 1, null, null).label.length() == WaitEntry.MAX_LABEL, "entry: label capped");
        expect(new WaitEntry("k", null, 1, null, null).label.isEmpty(), "entry: null label is empty");
        expect(new WaitEntry(null, "l", 1, null, null).key.isEmpty(), "entry: null key is empty");
        expect(new WaitEntry("k", "l", 100, null, null).ready(100) && new WaitEntry("k", "l", 100, null, null).ready(101)
                && !new WaitEntry("k", "l", 100, null, null).ready(99), "entry: ready exactly at its time");
    }

    // ------------------------------------------------------------------ ping rules

    static void pingPolicy() {
        Profile p = new Profile();
        long t = 10 * DAY + 5 * HOUR;
        expect(PingPolicy.allowed(p, t, 30, 3), "ping: a fresh profile may be pinged");
        PingPolicy.record(p, t);
        expect(p.pingCount == 1 && p.lastPingAt == t && p.pingDay == PingPolicy.dayOf(t), "ping: record notes the time, day and count");
        expect(!PingPolicy.allowed(p, t + 29 * MIN, 30, 3), "ping: too soon after the last one");
        expect(!PingPolicy.allowed(p, t + 30 * MIN - 1, 30, 3), "ping: one millisecond early is still too soon");
        expect(PingPolicy.allowed(p, t + 30 * MIN, 30, 3), "ping: allowed again exactly after the gap");
        PingPolicy.record(p, t + HOUR);
        PingPolicy.record(p, t + 2 * HOUR);
        expect(p.pingCount == 3, "ping: three today");
        expect(!PingPolicy.allowed(p, t + 3 * HOUR, 30, 3), "ping: the daily limit stops the fourth");
        expect(PingPolicy.allowed(p, t + 3 * HOUR, 30, 4), "ping: a higher limit lets it through");
        long tomorrow = PingPolicy.dayOf(t) * DAY + DAY + MIN;
        expect(PingPolicy.allowed(p, tomorrow, 30, 3), "ping: a new day resets the limit");
        PingPolicy.record(p, tomorrow);
        expect(p.pingCount == 1 && p.pingDay == PingPolicy.dayOf(tomorrow), "ping: counting starts over on a new day");
        expect(!PingPolicy.allowed(p, tomorrow - 1, 30, 3), "ping: clock going backwards does not allow it");
        expect(PingPolicy.dayOf(0) == 0 && PingPolicy.dayOf(DAY - 1) == 0 && PingPolicy.dayOf(DAY) == 1, "ping: day index");
        expect(PingPolicy.dayOf(-1) == -1, "ping: day index before 1970 floors");

        ArrayDeque<Long> recent = new ArrayDeque<>();
        expect(PingPolicy.serverHasRoom(recent, t, 2), "server: empty has room");
        recent.addLast(t);
        recent.addLast(t + MIN);
        expect(!PingPolicy.serverHasRoom(recent, t + 2 * MIN, 2), "server: full within the hour");
        expect(PingPolicy.serverHasRoom(recent, t + 2 * MIN, 3), "server: higher limit has room");
        expect(!PingPolicy.serverHasRoom(recent, t + HOUR - 1, 2) && recent.size() == 2, "server: 59m59s later still full, nothing dropped");
        expect(PingPolicy.serverHasRoom(recent, t + HOUR, 2) && recent.size() == 1, "server: an hour later the oldest is forgotten");
        expect(PingPolicy.serverHasRoom(recent, t + 2 * HOUR, 1) && recent.isEmpty(), "server: all forgotten");
    }

    // ------------------------------------------------------------------ discord

    static void discordMessage() {
        expect(DiscordMessage.validUserId("123456789012345678") && DiscordMessage.validUserId("12345678901234567")
                && DiscordMessage.validUserId("12345678901234567890"), "discord: 17, 18 and 20 digits are valid ids");
        expect(!DiscordMessage.validUserId("1234567890123456") && !DiscordMessage.validUserId("123456789012345678901")
                && !DiscordMessage.validUserId("12345678901234567a") && !DiscordMessage.validUserId("") && !DiscordMessage.validUserId(null)
                && !DiscordMessage.validUserId(" 123456789012345678") && !DiscordMessage.validUserId("<@123456789012345678>"), "discord: bad ids");
        expect(DiscordMessage.validWebhook("https://discord.com/api/webhooks/123/abcDEF") && DiscordMessage.validWebhook("https://discordapp.com/api/webhooks/123/abc")
                && DiscordMessage.validWebhook("  https://discord.com/api/webhooks/123/abc  "), "discord: real webhook addresses");
        expect(!DiscordMessage.validWebhook(null) && !DiscordMessage.validWebhook("") && !DiscordMessage.validWebhook("http://discord.com/api/webhooks/123/abc")
                && !DiscordMessage.validWebhook("https://evil.example/api/webhooks/123/abc") && !DiscordMessage.validWebhook("https://discord.com/api/webhooks/")
                && !DiscordMessage.validWebhook("https://discord.com.evil.example/api/webhooks/1/a") && !DiscordMessage.validWebhook("https://discord.com/api/webhooks/1 2/ab")
                && !DiscordMessage.validWebhook("https://discord.com/api/webhooks/1"), "discord: anything else is refused");

        expect(DiscordMessage.plain("&aHello &lWorld").equals("Hello World") && DiscordMessage.plain("§cRed").equals("Red"), "plain: colour codes go");
        expect(DiscordMessage.plain("hi @everyone <@123>").equals("hi everyone ‹123›"), "plain: no @ and no angle brackets: " + DiscordMessage.plain("hi @everyone <@123>"));
        expect(DiscordMessage.plain("a\nb\r\nc\td").equals("a b  c d"), "plain: line breaks and tabs become spaces: '" + DiscordMessage.plain("a\nb\r\nc\td") + "'");
        expect(DiscordMessage.plain(null).isEmpty() && DiscordMessage.plain("  x  ").equals("x"), "plain: null and trimming");

        String tpl = "Something is waiting on {server}: {what}";
        expect(DiscordMessage.compose(tpl, "Ann", "RS", List.of("Fluffy grew up")).equals("Something is waiting on RS: Fluffy grew up"), "compose: one label");
        expect(DiscordMessage.compose(tpl, "Ann", "RS", List.of("A", "B", "C")).equals("Something is waiting on RS: A; B; C"), "compose: three labels all shown");
        expect(DiscordMessage.compose(tpl, "Ann", "RS", List.of("A", "B", "C", "D", "E")).equals("Something is waiting on RS: A; B; C; +2 more"), "compose: more than three: "
                + DiscordMessage.compose(tpl, "Ann", "RS", List.of("A", "B", "C", "D", "E")));
        expect(DiscordMessage.compose(tpl, "Ann", "RS", List.of("A", "B", "C", "D")).endsWith("C; +1 more"), "compose: exactly one extra");
        expect(DiscordMessage.compose("Hi {player}", "Ann", "RS", List.of()).equals("Hi Ann"), "compose: player placeholder");
        expect(DiscordMessage.compose(tpl, "Ann", "RS", List.of("@everyone &chello <@1>")).equals("Something is waiting on RS: everyone hello ‹1›"),
                "compose: a label cannot mention anyone: " + DiscordMessage.compose(tpl, "Ann", "RS", List.of("@everyone &chello <@1>")));
        expect(DiscordMessage.compose("Hi @here", "Ann", "RS", List.of()).equals("Hi here"), "compose: a template cannot mention anyone either");
        String longText = DiscordMessage.compose(tpl, "Ann", "RS", List.of("x".repeat(500)));
        expect(longText.length() == DiscordMessage.MAX_CONTENT - 30 && longText.endsWith("…"), "compose: cut with an ellipsis: " + longText.length());
        String exact = DiscordMessage.compose("{what}", "Ann", "RS", List.of("y".repeat(DiscordMessage.MAX_CONTENT - 30)));
        expect(exact.length() == DiscordMessage.MAX_CONTENT - 30 && !exact.endsWith("…"), "compose: exactly at the limit is left alone: " + exact.length());

        String json = DiscordMessage.json("123456789012345678", "Hello \"you\"", "Real Society");
        expect(json.equals("{\"content\":\"<@123456789012345678> Hello \\\"you\\\"\",\"username\":\"Real Society\",\"allowed_mentions\":{\"parse\":[],\"users\":[\"123456789012345678\"]}}"), "json: exact body: " + json);
        expect(!DiscordMessage.json("123456789012345678", "x", "").contains("username") && !DiscordMessage.json("123456789012345678", "x", null).contains("username")
                && !DiscordMessage.json("123456789012345678", "x", "  ").contains("username"), "json: no username when there is none");
        expect(DiscordMessage.json("123456789012345678", "x", "&aBob@here").contains("\"username\":\"Bobhere\""), "json: username is cleaned too");
        expect(DiscordMessage.escape("a\\b").equals("a\\\\b") && DiscordMessage.escape("l1\nl2").equals("l1\\nl2") && DiscordMessage.escape("r\rt\t").equals("r\\rt\\t"), "escape: backslash and breaks");
        expect(DiscordMessage.escape("\u0001").equals("\\u0001") && DiscordMessage.escape("\u001f").equals("\\u001f") && DiscordMessage.escape(" ").equals(" "), "escape: control characters");
        expect(DiscordMessage.escape("café ✔").equals("café ✔"), "escape: ordinary unicode is left as is");
    }

    // ------------------------------------------------------------------ the ledger

    static File tmp() throws Exception {
        return Files.createTempDirectory("nwait-logic").toFile();
    }

    static WaitEntry e(String key, long readyAt) {
        return new WaitEntry(key, "label " + key, readyAt, null, null);
    }

    static void store() throws Exception {
        File d = tmp();
        List<String> warns = new ArrayList<>();
        WaitingStore s = new WaitingStore(new File(d, "t.tsv"), new File(d, "p.tsv"), warns::add);
        expect(s.all(A).isEmpty() && s.count(A) == 0 && s.get(A, "x") == null && s.timerCount() == 0, "store: starts empty");
        expect(s.put(A, e("one", 1000), 3), "store: put works");
        expect(s.get(A, "one").label.equals("label one") && s.count(A) == 1, "store: get and count");
        expect(s.get(A, " one ") != null, "store: lookups clean the key the same way");
        expect(s.put(A, e("two", 2000), 3) && s.put(A, e("three", 3000), 3), "store: fill to the cap");
        expect(!s.put(A, e("four", 4000), 3) && s.count(A) == 3 && s.get(A, "four") == null, "store: a new key over the cap is refused");
        expect(s.put(A, e("one", 5000), 3) && s.get(A, "one").readyAt == 5000, "store: an existing key can still be replaced at the cap");
        expect(!s.put(A, new WaitEntry("", "x", 1, null, null), 3), "store: a blank key is refused");
        expect(s.put(B, e("one", 1), 3) && s.get(B, "one").readyAt == 1 && s.get(A, "one").readyAt == 5000, "store: players are separate");

        // replacing: same ready time keeps "told", a new ready time does not
        s.setNotified(A, "two", true);
        s.put(A, e("two", 2000), 3);
        expect(s.get(A, "two").notified, "store: same key and ready time stays told (a restart must not ping twice)");
        s.put(A, e("two", 2500), 3);
        expect(!s.get(A, "two").notified, "store: a new ready time is news again");

        s.setNotified(A, "nothing", true);
        expect(s.get(A, "nothing") == null, "store: marking a missing timer does nothing");
        expect(s.remove(A, "three") && !s.remove(A, "three") && s.count(A) == 2, "store: remove once");
        expect(s.remove(B, "one") && s.count(B) == 0 && s.all(B).isEmpty(), "store: removing the last timer empties the player");
        expect(!s.remove(UUID.randomUUID(), "x"), "store: removing from a stranger");

        // order and visibility
        WaitingStore o = new WaitingStore(new File(d, "t2.tsv"), new File(d, "p2.tsv"), warns::add);
        long now = 10_000;
        o.put(A, e("later", 20_000), 40);
        o.put(A, e("soon", 15_000), 40);
        o.put(A, e("done-new", 9_000), 40);
        o.put(A, e("done-old", 2_000), 40);
        o.put(A, new WaitEntry("errand:1", "scout", 1_000, "errand", "woods"), 40);
        List<String> order = new ArrayList<>();
        for (WaitEntry x : o.visible(A, now)) {
            order.add(x.key);
        }
        expect(order.equals(List.of("done-old", "done-new", "soon", "later")), "store: ready first (oldest first), then soonest; errands are not in the list: " + order);
        expect(o.readyCount(A, now) == 3, "store: ready count includes errands: " + o.readyCount(A, now));
        expect(o.readyCount(A, 1_000) == 1 && o.readyCount(A, 999) == 0, "store: ready exactly at the moment");
        o.put(A, e("tie-b", 30_000), 40);
        o.put(A, e("tie-a", 30_000), 40);
        List<String> ties = new ArrayList<>();
        for (WaitEntry x : o.visible(A, now)) {
            ties.add(x.key);
        }
        expect(ties.subList(ties.size() - 2, ties.size()).equals(List.of("tie-a", "tie-b")), "store: equal times fall back to the key: " + ties);

        // due
        List<WaitingStore.Due> due = o.due(now);
        List<String> dueKeys = new ArrayList<>();
        for (WaitingStore.Due x : due) {
            dueKeys.add(x.entry.key);
        }
        expect(dueKeys.equals(List.of("errand:1", "done-old", "done-new")), "store: due = ready and not yet told, oldest first: " + dueKeys);
        o.setNotified(A, "done-old", true);
        expect(o.due(now).size() == 2 && o.due(9_000 - 1).size() == 1, "store: told timers are not due again; unready ones are not due");

        // cleanup keeps errands, drops old finished things
        expect(o.cleanup(now + 100 * DAY, 30 * DAY) == 6 && o.get(A, "errand:1") != null && o.count(A) == 1, "store: cleanup drops old timers but never an errand: " + o.count(A));
        expect(o.cleanup(now + 100 * DAY, 30 * DAY) == 0, "store: nothing more to clean");
        WaitingStore c = new WaitingStore(new File(d, "t3.tsv"), new File(d, "p3.tsv"), warns::add);
        c.put(A, e("edge", 1000), 40);
        expect(c.cleanup(1000 + 30 * DAY, 30 * DAY) == 0 && c.cleanup(1000 + 30 * DAY + 1, 30 * DAY) == 1, "store: cleanup age is exact");
        expect(c.timerCount() == 0, "store: cleanup empties the player");

        // profiles
        expect(s.peekProfile(A) == null, "profile: none until asked for");
        Profile p = s.profile(A);
        expect(p == s.profile(A) && s.peekProfile(A) == p && p.enabled && p.popup && !p.linked(), "profile: created once, defaults on");
        p.discordId = "123456789012345678";
        expect(p.linked() && s.linkedCount() == 1, "profile: linked count");
    }

    static void storeFiles() throws Exception {
        File d = tmp();
        List<String> warns = new ArrayList<>();
        File tf = new File(d, "timers.tsv");
        File pf = new File(d, "profiles.tsv");
        WaitingStore s = new WaitingStore(tf, pf, warns::add);
        s.flush();
        expect(!tf.exists() && !pf.exists(), "files: nothing is written when nothing changed");
        s.put(A, new WaitEntry("hatchling:1", "Fluffy\tgrew up", 123456789L, "api", "extra"), 40);
        s.put(A, new WaitEntry("errand:1", "scout back", 5, "errand", "woods"), 40);
        s.setNotified(A, "errand:1", true);
        Profile p = s.profile(B);
        p.discordId = "123456789012345678";
        p.enabled = false;
        p.popup = false;
        p.lastPingAt = 77;
        p.pingDay = 19000;
        p.pingCount = 2;
        p.name = "Bob\tthe";
        s.markDirty();
        s.flush();
        expect(tf.exists() && pf.exists() && !new File(tf.getPath() + ".tmp").exists() && !new File(pf.getPath() + ".tmp").exists(), "files: written, no temp files left");

        WaitingStore r = new WaitingStore(tf, pf, warns::add);
        r.load();
        WaitEntry h = r.get(A, "hatchling:1");
        expect(h != null && h.readyAt == 123456789L && h.label.equals("Fluffy grew up") && h.source.equals("api") && h.data.equals("extra") && !h.notified, "files: a timer round-trips");
        WaitEntry er = r.get(A, "errand:1");
        expect(er != null && er.isErrand() && er.notified && er.data.equals("woods") && er.readyAt == 5, "files: errand and told flag round-trip");
        Profile q = r.peekProfile(B);
        expect(q != null && q.discordId.equals("123456789012345678") && !q.enabled && !q.popup && q.lastPingAt == 77 && q.pingDay == 19000
                && q.pingCount == 2 && q.name.equals("Bob the"), "files: a profile round-trips");
        expect(warns.isEmpty(), "files: no warnings for good files: " + warns);

        // a flush with nothing changed afterwards writes nothing
        long mod = tf.lastModified();
        r.flush();
        expect(tf.lastModified() == mod, "files: second flush with no change leaves the file alone");

        // bad lines are skipped and counted
        Files.write(tf.toPath(), List.of("# header", "", "not-a-uuid\tk\t1\t0\tapi\t\tlabel",
                A + "\tgood\t10\t0\tapi\t\tgood one", A + "\tshort\t10", A + "\tbadtime\tNaN\t0\tapi\t\tx", A + "\t\t10\t0\tapi\t\tno key"), StandardCharsets.UTF_8);
        Files.write(pf.toPath(), List.of(B + "\tnot-digits\t1\t0\t0\t0\t1\tBob", B + "\t\t1\t0\t0\t0\t1", "garbage"), StandardCharsets.UTF_8);
        warns.clear();
        WaitingStore bad = new WaitingStore(tf, pf, warns::add);
        bad.load();
        expect(bad.count(A) == 1 && bad.get(A, "good").label.equals("good one"), "files: only the good line is kept");
        expect(bad.peekProfile(B) != null && !bad.peekProfile(B).linked(), "files: an invalid Discord id in the file is dropped, the profile stays");
        expect(warns.size() == 1 && warns.get(0).contains(" 6 "), "files: one warning counting the 6 bad lines: " + warns);

        // load replaces what was in memory
        bad.put(B, e("mem", 1), 40);
        bad.load();
        expect(bad.get(B, "mem") == null, "files: load starts from the file");
        // missing files are fine
        WaitingStore none = new WaitingStore(new File(d, "nope1"), new File(d, "nope2"), warns::add);
        none.load();
        expect(none.timerCount() == 0, "files: missing files load as empty");
        // unwritable location is reported, memory kept
        File blocker = new File(d, "blocker");
        Files.write(blocker.toPath(), List.of("x"), StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        WaitingStore ro = new WaitingStore(new File(blocker, "t.tsv"), new File(blocker, "p.tsv"), w2::add);
        ro.put(A, e("k", 1), 40);
        ro.flush();
        expect(!w2.isEmpty() && ro.get(A, "k") != null, "files: a write failure is reported and nothing is lost: " + w2);
    }

    // ------------------------------------------------------------------ errand rolls

    static void rolls() {
        Reward fixed = new Reward(Material.COAL, "", 5, 5, 1);
        Destination d = new Destination("mine", "&7Mine", Material.IRON_PICKAXE, 60, List.of("l"), 3, List.of(fixed), 0, List.of(), List.of());
        Haul h = d.roll(new Random(1));
        expect(h.items.size() == 1 && h.items.get(Material.COAL) == 15 && !h.jackpot && h.story.isEmpty() && h.commands.isEmpty(), "roll: 3 rolls of exactly 5 merge into 15: " + h.items);
        expect(d.millis() == 60 * MIN, "roll: minutes to millis");

        // amount range and weights
        Reward a = new Reward(Material.COAL, "", 2, 4, 9);
        Reward b = new Reward(Material.STICK, "", 1, 1, 1);
        Destination w = new Destination("w", "W", Material.COAL, 1, List.of(), 1, List.of(a, b), 0, List.of(), List.of());
        int coal = 0, stick = 0, low = 99, high = 0;
        Random r = new Random(42);
        for (int i = 0; i < 4000; i++) {
            Haul x = w.roll(r);
            if (x.items.containsKey(Material.COAL)) {
                coal++;
                int n = x.items.get(Material.COAL);
                low = Math.min(low, n);
                high = Math.max(high, n);
            } else {
                stick++;
            }
        }
        expect(low == 2 && high == 4, "roll: amounts span min..max: " + low + ".." + high);
        expect(coal > 3400 && coal < 3800 && stick > 200 && stick < 600, "roll: weights 9:1 are respected: " + coal + "/" + stick);

        // deterministic for one seed
        Haul s1 = w.roll(new Random(7));
        Haul s2 = w.roll(new Random(7));
        expect(s1.items.equals(s2.items), "roll: same seed, same haul");

        // jackpot
        Reward gem = new Reward(Material.DIAMOND, "", 1, 1, 1);
        Destination never = new Destination("n", "N", Material.COAL, 1, List.of(), 1, List.of(fixed), 0, List.of(gem), List.of());
        Destination always = new Destination("a", "A", Material.COAL, 1, List.of(), 1, List.of(fixed), 100, List.of(gem), List.of());
        Destination nopool = new Destination("p", "P", Material.COAL, 1, List.of(), 1, List.of(fixed), 100, List.of(), List.of());
        boolean sawJackpot = false;
        for (int i = 0; i < 3000; i++) {
            sawJackpot |= never.roll(new Random(i)).jackpot;
        }
        expect(!sawJackpot, "roll: 0% jackpot never happens");
        Haul j = always.roll(new Random(3));
        expect(j.jackpot && j.items.get(Material.DIAMOND) == 1 && j.items.get(Material.COAL) == 5, "roll: 100% jackpot always adds the find: " + j.items);
        expect(!nopool.roll(new Random(3)).jackpot, "roll: a jackpot with nothing in it is not a jackpot");
        Destination some = new Destination("s", "S", Material.COAL, 1, List.of(), 1, List.of(fixed), 30, List.of(gem), List.of());
        int hits = 0;
        Random jr = new Random(9);
        for (int i = 0; i < 5000; i++) {
            if (some.roll(jr).jackpot) {
                hits++;
            }
        }
        expect(hits > 1300 && hits < 1700, "roll: 30% jackpot is about 30%: " + hits);

        // commands and stories
        Reward pay = new Reward(null, "eco give {player} 5", 1, 1, 1);
        expect(pay.isCommand() && !fixed.isCommand(), "reward: command or item");
        Destination bank = new Destination("b", "B", Material.GOLD_INGOT, 1, List.of(), 2, List.of(pay), 0, List.of(), List.of("one", "two"));
        Haul bh = bank.roll(new Random(5));
        expect(bh.commands.equals(List.of("eco give {player} 5", "eco give {player} 5")) && bh.items.isEmpty() && !bh.isEmpty(), "roll: command rewards are collected: " + bh.commands);
        expect(bh.story.equals("one") || bh.story.equals("two"), "roll: a story is picked");
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(bank.roll(new Random(i)).story);
        }
        expect(seen.equals(Set.of("one", "two")), "roll: both stories show up: " + seen);
        expect(new Haul().isEmpty(), "haul: empty by default");

        // reward limits
        Reward weird = new Reward(Material.COAL, null, -5, -9, 0);
        expect(weird.min == 1 && weird.max == 1 && weird.weight == 1 && weird.command.isEmpty(), "reward: nonsense numbers are fixed");
        Reward big = new Reward(Material.COAL, "", 1, 500, 1);
        expect(big.max == Reward.MAX_AMOUNT && new Reward(Material.COAL, "", 900, 1000, 1).min == Reward.MAX_AMOUNT, "reward: amounts are capped at a stack");
        expect(new Reward(Material.COAL, "", 5, 2, 1).max == 5, "reward: max is never below min");

        // slots
        Haul sl = new Haul();
        sl.items.put(Material.COAL, 64);
        sl.items.put(Material.STICK, 65);
        sl.items.put(Material.DIAMOND, 1);
        expect(sl.slotsNeeded(m -> 64) == 1 + 2 + 1, "haul: slots in whole stacks: " + sl.slotsNeeded(m -> 64));
        expect(sl.slotsNeeded(m -> m == Material.DIAMOND ? 1 : 16) == 4 + 5 + 1, "haul: smaller stacks need more slots: " + sl.slotsNeeded(m -> m == Material.DIAMOND ? 1 : 16));
        expect(new Haul().slotsNeeded(m -> 64) == 0 && sl.slotsNeeded(m -> 0) == 64 + 65 + 1, "haul: an empty haul needs nothing; a zero stack size counts as 1");
        Destination one = new Destination("o", "O", Material.COAL, 1, List.of(), 1, List.of(fixed), 150, List.of(gem), List.of());
        expect(one.jackpotPercent == 100, "destination: percent is capped at 100");
    }

    // ------------------------------------------------------------------ popup mode

    static void popupMode() {
        expect(PopupMode.parse("when-ready", null) == PopupMode.WHEN_READY && PopupMode.parse("WHEN_READY", null) == PopupMode.WHEN_READY, "mode: when-ready");
        expect(PopupMode.parse("always", null) == PopupMode.ALWAYS && PopupMode.parse(" Always ", null) == PopupMode.ALWAYS, "mode: always");
        expect(PopupMode.parse("never", null) == PopupMode.NEVER && PopupMode.parse("off", null) == PopupMode.NEVER, "mode: never and off");
        expect(PopupMode.parse("sometimes", PopupMode.ALWAYS) == PopupMode.ALWAYS && PopupMode.parse(null, PopupMode.NEVER) == PopupMode.NEVER && PopupMode.parse("x", null) == null, "mode: unknown gives the fallback");
        expect(PopupMode.WHEN_READY.shouldPopup(1) && !PopupMode.WHEN_READY.shouldPopup(0), "mode: when-ready needs something ready");
        expect(PopupMode.ALWAYS.shouldPopup(0) && PopupMode.ALWAYS.shouldPopup(5), "mode: always always");
        expect(!PopupMode.NEVER.shouldPopup(0) && !PopupMode.NEVER.shouldPopup(5), "mode: never never");
    }

    // ------------------------------------------------------------------ config

    static Kit.MapSection base() {
        return new Kit.MapSection()
                .put("errands.destinations.woods.name", "&aThe Woods").put("errands.destinations.woods.icon", "OAK_SAPLING")
                .put("errands.destinations.woods.minutes", 15).put("errands.destinations.woods.rolls", 2)
                .put("errands.destinations.woods.lore", List.of("a walk"))
                .put("errands.destinations.woods.rewards.logs.item", "OAK_LOG").put("errands.destinations.woods.rewards.logs.min", 4)
                .put("errands.destinations.woods.rewards.logs.max", 12).put("errands.destinations.woods.rewards.logs.weight", 10)
                .put("errands.destinations.woods.stories", List.of("s1", "s2"));
    }

    static void config() {
        WaitingConfig d = new WaitingConfig();
        d.load(new Kit.MapSection());
        expect(d.popup == PopupMode.WHEN_READY && d.delaySeconds == 8 && d.waitForLogin && d.loginTimeoutSeconds == 180, "config: login defaults");
        expect(d.checkIntervalSeconds == 30 && d.notifyOnline && d.cleanupDays == 30 && d.maxTimersPerPlayer == 40, "config: general defaults");
        expect(!d.discordEnabled && !d.discordReady() && d.minMinutesBetweenPings == 30 && d.maxPingsPerDay == 3 && d.maxPingsPerHour == 30
                && d.skipOlderThanHours == 24, "config: discord defaults");
        expect(d.errandsEnabled == false && d.destinations.isEmpty(), "config: no destinations turns errands off");
        expect(d.warnings.size() == 1 && d.warnings.get(0).contains("no destination"), "config: ...and says so: " + d.warnings);
        expect(d.baseSlots == 1 && d.maxSlots == 4 && d.filler && d.fillerIcon == Material.GRAY_STAINED_GLASS_PANE, "config: slots and menu defaults");
        expect(d.msg("prefix").equals(WaitingConfig.DEFAULT_MESSAGES.get("prefix")) && d.msg("no-such-key").equals("no-such-key"), "config: messages fall back to the built-in text, then to the key");

        WaitingConfig c = new WaitingConfig();
        c.load(base().put("login.popup", "bogus").put("login.delay-seconds", 99999).put("login.timeout-seconds", 1).put("check-interval-seconds", 1)
                .put("cleanup-days", 0).put("max-timers-per-player", 99999).put("errands.max-slots", 9).put("errands.slots", 7)
                .put("discord.min-minutes-between-pings", 0).put("discord.max-pings-per-day", 0).put("discord.max-pings-per-hour-server", 0)
                .put("discord.skip-older-than-hours", 0).put("menu.filler-icon", "NOT_A_THING").put("messages.prefix", "[X] "));
        expect(c.popup == PopupMode.WHEN_READY && c.warnings.stream().anyMatch(x -> x.contains("login.popup")), "config: unknown popup mode warns: " + c.warnings);
        expect(c.delaySeconds == 600 && c.loginTimeoutSeconds == 5 && c.checkIntervalSeconds == 5 && c.cleanupDays == 1 && c.maxTimersPerPlayer == 500, "config: numbers are clamped");
        expect(c.maxSlots == 4 && c.baseSlots == 4, "config: slots are clamped to max-slots and 4");
        expect(c.minMinutesBetweenPings == 1 && c.maxPingsPerDay == 1 && c.maxPingsPerHour == 1 && c.skipOlderThanHours == 1, "config: discord limits have a floor of 1");
        expect(c.fillerIcon == Material.GRAY_STAINED_GLASS_PANE, "config: bad filler icon falls back");
        expect(c.msg("prefix").equals("[X] ") && c.msg("login").equals(WaitingConfig.DEFAULT_MESSAGES.get("login")), "config: a message can be replaced, others stay");
        c.load(base().put("login.delay-seconds", -4).put("login.timeout-seconds", 99999).put("check-interval-seconds", 99999).put("cleanup-days", 99999).put("errands.max-slots", 0).put("errands.slots", 0));
        expect(c.delaySeconds == 0 && c.loginTimeoutSeconds == 3600 && c.checkIntervalSeconds == 600 && c.cleanupDays == 3650 && c.maxSlots == 1 && c.baseSlots == 1, "config: clamps at the other end");
        c.load(base().put("login.popup", "never").put("login.wait-for-login", false).put("notify-online", false));
        expect(c.popup == PopupMode.NEVER && !c.waitForLogin && !c.notifyOnline && c.warnings.isEmpty(), "config: values are read, a reload clears old warnings: " + c.warnings);
        expect(c.messages.isEmpty(), "config: a reload clears old messages");

        // destinations
        WaitingConfig w = new WaitingConfig();
        w.load(base());
        expect(w.errandsEnabled && w.destinations.size() == 1 && w.warnings.isEmpty(), "dest: one good destination: " + w.warnings);
        Destination woods = w.destination("woods");
        expect(woods != null && woods.name.equals("&aThe Woods") && woods.icon == Material.OAK_SAPLING && woods.minutes == 15 && woods.rolls == 2
                && woods.lore.equals(List.of("a walk")) && woods.stories.equals(List.of("s1", "s2")) && woods.rewards.size() == 1
                && woods.rewards.get(0).item == Material.OAK_LOG && woods.rewards.get(0).min == 4 && woods.rewards.get(0).max == 12 && woods.rewards.get(0).weight == 10
                && woods.jackpotPercent == 0 && woods.jackpot.isEmpty(), "dest: every field read");
        expect(w.destination("nope") == null, "dest: unknown id");

        WaitingConfig m = new WaitingConfig();
        m.load(base().put("errands.destinations.Mine Shaft!.name", "M")
                .put("errands.destinations.Mine Shaft!.rewards.c.item", "COAL")
                .put("errands.destinations.bad.rewards.x.item", "NOT_AN_ITEM")
                .put("errands.destinations.empty.name", "E")
                .put("errands.destinations.cmd.rewards.p.command", "/eco give {player} 10").put("errands.destinations.cmd.icon", "NOPE")
                .put("errands.destinations.cmd.minutes", 99999).put("errands.destinations.cmd.rolls", 99)
                .put("errands.destinations.cmd.jackpot.chance-percent", 500)
                .put("errands.destinations.cmd.jackpot.rewards.d.item", "DIAMOND").put("errands.destinations.cmd.jackpot.rewards.air.item", "AIR"));
        Destination shaft = m.destination("mineshaft");
        expect(shaft != null && shaft.icon == Material.COMPASS && shaft.minutes == 60 && shaft.rolls == 2 && shaft.rewards.get(0).min == 1 && shaft.rewards.get(0).max == 1,
                "dest: id is cleaned, defaults fill in: " + m.destinations.size());
        expect(m.destination("bad") == null && m.warnings.stream().anyMatch(x -> x.contains("NOT_AN_ITEM")) && m.warnings.stream().anyMatch(x -> x.contains("'bad'") && x.contains("no usable rewards")),
                "dest: a destination with no usable rewards is skipped, both problems reported: " + m.warnings);
        expect(m.destination("empty") == null, "dest: no rewards section at all is skipped");
        Destination cmd = m.destination("cmd");
        expect(cmd != null && cmd.rewards.get(0).isCommand() && cmd.rewards.get(0).command.equals("eco give {player} 10") && cmd.icon == Material.COMPASS
                && cmd.minutes == 10080 && cmd.rolls == 6 && cmd.jackpotPercent == 100, "dest: command reward without slash, icon fallback, clamps: " + (cmd == null ? "null" : cmd.minutes));
        expect(cmd.jackpot.size() == 1 && cmd.jackpot.get(0).item == Material.DIAMOND, "dest: AIR is not a valid item: " + cmd.jackpot.size());
        expect(m.warnings.stream().anyMatch(x -> x.contains("cmd jackpot") && x.contains("AIR")) && m.warnings.stream().anyMatch(x -> x.contains("NOPE")), "dest: warnings name the problem: " + m.warnings);

        // seven places at most, repeated ids
        Kit.MapSection many = base();
        for (int i = 0; i < 10; i++) {
            many.put("errands.destinations.p" + i + ".rewards.a.item", "COAL");
        }
        WaitingConfig seven = new WaitingConfig();
        seven.load(many);
        expect(seven.destinations.size() == 7, "dest: at most seven places: " + seven.destinations.size());
        WaitingConfig dup = new WaitingConfig();
        dup.load(base().put("errands.destinations.WOODS.rewards.a.item", "COAL"));
        expect(dup.destinations.size() == 1 && dup.warnings.stream().anyMatch(x -> x.contains("repeated")), "dest: ids that collide after cleaning are reported: " + dup.warnings);
        WaitingConfig off = new WaitingConfig();
        off.load(base().put("errands.enabled", false));
        expect(!off.errandsEnabled && off.destinations.size() == 1 && off.warnings.isEmpty(), "dest: errands can be switched off");

        // discord
        WaitingConfig dc = new WaitingConfig();
        dc.load(base().put("discord.enabled", true));
        expect(dc.discordEnabled && !dc.discordReady() && dc.warnings.size() == 1 && dc.warnings.get(0).contains("empty"), "discord: enabled without an address warns: " + dc.warnings);
        dc.load(base().put("discord.enabled", true).put("discord.webhook-url", "http://nope"));
        expect(!dc.discordReady() && dc.warnings.size() == 1 && dc.warnings.get(0).contains("does not look like"), "discord: a wrong address warns: " + dc.warnings);
        expect(dc.warnings.get(0).indexOf("nope") < 0, "discord: the address is never echoed");
        dc.load(base().put("discord.enabled", true).put("discord.webhook-url", "https://discord.com/api/webhooks/1/abc").put("discord.username", "U").put("discord.server-name", "S").put("discord.message", "M {what}"));
        expect(dc.discordReady() && dc.warnings.isEmpty() && dc.discordUsername.equals("U") && dc.serverName.equals("S") && dc.discordMessage.equals("M {what}"), "discord: ready when good");
        dc.load(base().put("discord.enabled", false).put("discord.webhook-url", "https://discord.com/api/webhooks/1/abc"));
        expect(!dc.discordReady(), "discord: an address alone is not enough");
    }

    // ------------------------------------------------------------------ the real HTTP sender

    static void webhook() throws Exception {
        List<String> bodies = new ArrayList<>();
        List<String> types = new ArrayList<>();
        AtomicReference<Integer> status = new AtomicReference<>(204);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            synchronized (bodies) {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                types.add(ex.getRequestHeaders().getFirst("Content-Type") + "|" + ex.getRequestMethod());
            }
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            HttpWebhook hook = new HttpWebhook(url);
            AtomicReference<Boolean> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            hook.post("{\"content\":\"café ✔\"}", ok -> {
                result.set(ok);
                done.countDown();
            });
            expect(done.await(10, TimeUnit.SECONDS) && Boolean.TRUE.equals(result.get()), "http: a 204 is success");
            synchronized (bodies) {
                expect(bodies.size() == 1 && bodies.get(0).equals("{\"content\":\"café ✔\"}"), "http: body arrives intact as UTF-8: " + bodies);
                expect(types.get(0).startsWith("application/json") && types.get(0).endsWith("|POST"), "http: JSON content type, POST: " + types);
            }
            status.set(429);
            CountDownLatch d2 = new CountDownLatch(1);
            AtomicReference<Boolean> r2 = new AtomicReference<>();
            hook.post("{}", ok -> {
                r2.set(ok);
                d2.countDown();
            });
            expect(d2.await(10, TimeUnit.SECONDS) && Boolean.FALSE.equals(r2.get()), "http: a 429 is a failure");
            status.set(500);
            CountDownLatch d3 = new CountDownLatch(1);
            AtomicReference<Boolean> r3 = new AtomicReference<>();
            hook.post("{}", ok -> {
                r3.set(ok);
                d3.countDown();
            });
            expect(d3.await(10, TimeUnit.SECONDS) && Boolean.FALSE.equals(r3.get()), "http: a 500 is a failure");
            status.set(200);
            CountDownLatch d4 = new CountDownLatch(1);
            AtomicReference<Boolean> r4 = new AtomicReference<>();
            hook.post("{}", ok -> {
                r4.set(ok);
                d4.countDown();
            });
            expect(d4.await(10, TimeUnit.SECONDS) && Boolean.TRUE.equals(r4.get()), "http: any 2xx is success");
        } finally {
            server.stop(0);
        }
        // nothing listening: failure, not an exception
        HttpWebhook dead = new HttpWebhook("http://127.0.0.1:1/hook");
        CountDownLatch d5 = new CountDownLatch(1);
        AtomicReference<Boolean> r5 = new AtomicReference<>();
        dead.post("{}", ok -> {
            r5.set(ok);
            d5.countDown();
        });
        expect(d5.await(15, TimeUnit.SECONDS) && Boolean.FALSE.equals(r5.get()), "http: connection refused is a failure");
        boolean threw = false;
        try {
            new HttpWebhook("not a uri at all");
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        expect(threw, "http: an unusable address is refused when created");
    }


    // ------------------------------------------------------------------ gaps the mutation pass found

    static void hardening() throws Exception {
        // names, limits, boundaries
        expect(new WaitEntry("a".repeat(WaitEntry.MAX_KEY), "l", 1, null, null).key.length() == WaitEntry.MAX_KEY, "entry: a key of exactly the limit is untouched");
        expect(new WaitEntry("k", "x".repeat(WaitEntry.MAX_LABEL), 1, null, null).label.length() == WaitEntry.MAX_LABEL, "entry: a label of exactly the limit is untouched");
        expect(PingPolicy.allowed(new Profile(), 1000, 30, 3), "ping: a brand new profile is allowed even right after 1970");
        Profile zero = new Profile();
        zero.lastPingAt = 1;
        expect(!PingPolicy.allowed(zero, 2, 30, 3), "ping: a ping at time 1 still counts");

        expect(DiscordMessage.validWebhook("https://ptb.discord.com/api/webhooks/123/abc-DEF_1.2") && DiscordMessage.validWebhook("https://canary.discord.com/api/webhooks/123/abc")
                && DiscordMessage.validWebhook("https://discord.com/api/webhooks/123/abc?wait=true&thread_id=5"), "discord: ptb, canary and options are fine");
        expect(!DiscordMessage.validWebhook("https://discord.com/api/webhooks/abc/def") && !DiscordMessage.validWebhook("https://discord.com/api/webhooks/1/")
                && !DiscordMessage.validWebhook("https://discord.com/api/webhooks/1/abc def") && !DiscordMessage.validWebhook("https://discord.com/api/webhooks/1/abc/extra")
                && !DiscordMessage.validWebhook("https://discord.com/api/webhooks//abc") && !DiscordMessage.validWebhook("https://xdiscord.com/api/webhooks/1/abc"), "discord: malformed addresses refused");

        boolean threw = false;
        try {
            new Destination("d", "D", Material.COAL, 1, List.of(), 1, List.of(), 0, List.of(), List.of());
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        expect(threw, "destination: needs at least one reward");

        // the sender with an address the HTTP client refuses
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> res = new AtomicReference<>();
        new HttpWebhook("file:///nope").post("{}", ok -> {
            res.set(ok);
            done.countDown();
        });
        expect(done.await(10, TimeUnit.SECONDS) && Boolean.FALSE.equals(res.get()), "http: an address the client cannot use is a failure, not an exception");

        // warnings say what they mean
        WaitingConfig c = new WaitingConfig();
        c.load(LogicTest.base().put("discord.enabled", true));
        expect(c.warnings.get(0).startsWith("discord.enabled is true"), "config: warning text: " + c.warnings);
        c.load(new Kit.MapSection());
        expect(c.warnings.get(0).startsWith("errands.enabled is true"), "config: errands warning text: " + c.warnings);

        // every message the code uses has a built-in text, and none is left over
        List<String> used = List.of("prefix", "login", "ready-online", "dismissed", "not-ready", "errand-label", "errand-sent", "errand-busy", "errand-none", "errand-claim",
                "errand-jackpot", "errand-room", "errand-off", "discord-off-server", "discord-linked", "discord-bad-id", "discord-unlinked", "discord-on", "discord-off",
                "discord-test-sent", "discord-test-wait", "discord-not-linked", "discord-status-linked", "discord-status-none", "popup-on", "popup-off", "empty");
        expect(new java.util.HashSet<>(used).equals(WaitingConfig.DEFAULT_MESSAGES.keySet()), "messages: the built-in set is exactly the set the code uses: " + WaitingConfig.DEFAULT_MESSAGES.keySet());
        for (String k : used) {
            expect(!new WaitingConfig().msg(k).equals(k) && !new WaitingConfig().msg(k).isBlank(), "messages: '" + k + "' has text");
        }

        // menu text wrapping, at the edges
        expect(WaitingMenu.wrap("&7" + "x".repeat(38), 38).equals(List.of("&7" + "x".repeat(38))), "wrap: exactly the width stays one piece");
        expect(WaitingMenu.wrap("&7" + "x".repeat(39), 38).equals(List.of("&7" + "x".repeat(39))), "wrap: one unbreakable word is never cut");
        expect(WaitingMenu.wrap("&7" + "a".repeat(19) + " " + "b".repeat(18), 38).equals(List.of("&7" + "a".repeat(19) + " " + "b".repeat(18))), "wrap: two words that fit exactly stay together");
        expect(WaitingMenu.wrap("&7" + "a".repeat(19) + " " + "b".repeat(18) + " c", 38).equals(List.of("&7" + "a".repeat(19) + " " + "b".repeat(18), "&7c")), "wrap: a line that fills the width exactly is kept, the next word starts a new one");
        expect(WaitingMenu.wrap("&7" + "a".repeat(19) + " " + "b".repeat(19) + " c", 38).equals(List.of("&7" + "a".repeat(19), "&7" + "b".repeat(19) + " c")), "wrap: splits when a word would pass the width: "
                + WaitingMenu.wrap("&7" + "a".repeat(19) + " " + "b".repeat(19) + " c", 38));
        expect(WaitingMenu.wrap("&7" + "x".repeat(50) + " y", 38).equals(List.of("&7" + "x".repeat(50), "&7y")), "wrap: a too-long first word gets a line of its own, no empty line before it: "
                + WaitingMenu.wrap("&7" + "x".repeat(50) + " y", 38));
        expect(WaitingMenu.wrap("&7", 38).equals(List.of("&7")) && WaitingMenu.wrap("&", 38).equals(List.of("&")) && WaitingMenu.wrap("", 38).equals(List.of("")), "wrap: just a colour code, a lone & and nothing");
        expect(WaitingMenu.wrap("&6&l" + "ab ".repeat(20).trim(), 38).get(1).startsWith("&6&l"), "wrap: two leading codes both kept");
        expect(WaitingMenu.wrap("&7one two three four five six seven eight nine ten", 12).equals(List.of("&7one two", "&7three four", "&7five six", "&7seven eight", "&7nine ten")), "wrap: words are joined with single spaces: "
                + WaitingMenu.wrap("&7one two three four five six seven eight nine ten", 12));
    }

    // ------------------------------------------------------------------ files, the harder cases

    static void storeFilesHard() throws Exception {
        File d = tmp();
        List<String> warns = new ArrayList<>();
        File tf = new File(d, "timers.tsv");
        File pf = new File(d, "profiles.tsv");
        WaitingStore s = new WaitingStore(tf, pf, warns::add);
        s.put(A, e("one", 1000), 40);
        s.put(A, e("two", 2000), 40);
        s.profile(B).discordId = "123456789012345678";
        s.markDirty();
        s.flush();
        expect(Files.readAllLines(tf.toPath()).get(0).startsWith("# NexusWaiting timers") && Files.readAllLines(pf.toPath()).get(0).startsWith("# NexusWaiting profiles"), "hard: header lines");

        // putting exactly what is already there is not a change
        tf.setLastModified(1_000_000L);
        pf.setLastModified(1_000_000L);
        s.put(A, e("one", 1000), 40);
        s.flush();
        expect(tf.lastModified() == 1_000_000L && pf.lastModified() == 1_000_000L, "hard: an identical put writes nothing");
        WaitEntry same = new WaitEntry("one", "label one", 1000, null, null);
        s.put(A, same, 40);
        s.flush();
        expect(tf.lastModified() == 1_000_000L, "hard: ...also for a separately built identical timer");
        s.put(A, new WaitEntry("one", "other label", 1000, null, null), 40);
        s.flush();
        expect(tf.lastModified() != 1_000_000L && Files.readAllLines(tf.toPath()).stream().anyMatch(l -> l.contains("other label")), "hard: a changed label is written");
        tf.setLastModified(1_000_000L);
        s.put(A, new WaitEntry("one", "other label", 1000, "errand", null), 40);
        s.flush();
        expect(tf.lastModified() != 1_000_000L, "hard: a changed source is written");
        tf.setLastModified(1_000_000L);
        s.put(A, new WaitEntry("one", "other label", 1000, "errand", "woods"), 40);
        s.flush();
        expect(tf.lastModified() != 1_000_000L, "hard: a changed data field is written");
        tf.setLastModified(1_000_000L);
        s.put(A, new WaitEntry("one", "other label", 1001, "errand", "woods"), 40);
        s.flush();
        expect(tf.lastModified() != 1_000_000L, "hard: a changed time is written");

        // removing and marking are saved
        s.remove(A, "two");
        s.flush();
        WaitingStore r = new WaitingStore(tf, pf, warns::add);
        r.load();
        expect(r.get(A, "two") == null && r.get(A, "one") != null, "hard: a removal is saved");
        s.setNotified(A, "one", true);
        s.flush();
        r.load();
        expect(r.get(A, "one").notified, "hard: a told flag is saved");
        s.cleanup(1000 + 100 * DAY, 30 * DAY);
        s.flush();
        r.load();
        expect(r.get(A, "one") != null || r.timerCount() == 0, "hard: a cleanup is saved");
        WaitingStore c = new WaitingStore(tf, pf, warns::add);
        c.load();
        c.put(A, e("old", 10), 40);
        c.flush();
        c.remove(A, "gone");
        tf.setLastModified(1_000_000L);
        expect(c.cleanup(10 + DAY, 30 * DAY) == 0, "hard: nothing old enough");
        c.flush();
        expect(tf.lastModified() == 1_000_000L, "hard: a cleanup that removes nothing writes nothing");
        expect(c.cleanup(10 + 31 * DAY, 30 * DAY) >= 1, "hard: now it is old enough");
        c.flush();
        WaitingStore c2 = new WaitingStore(tf, pf, warns::add);
        c2.load();
        expect(c2.get(A, "old") == null, "hard: ...and the removal is saved");

        // flush twice: the second writes nothing
        c.put(A, e("fresh", 5), 40);
        c.flush();
        tf.setLastModified(1_000_000L);
        c.flush();
        expect(tf.lastModified() == 1_000_000L, "hard: a second flush with no change writes nothing");

        // a folder that does not exist yet is created
        File deep = new File(d, "a/b/c");
        WaitingStore nested = new WaitingStore(new File(deep, "t.tsv"), new File(deep, "p.tsv"), warns::add);
        nested.put(A, e("k", 1), 40);
        nested.flush();
        expect(new File(deep, "t.tsv").exists() && new File(deep, "p.tsv").exists(), "hard: missing folders are created");

        // a failed write is reported and retried later
        File blocker = new File(d, "blocker");
        Files.write(blocker.toPath(), List.of("x"), StandardCharsets.UTF_8);
        List<String> w2 = new ArrayList<>();
        WaitingStore ro = new WaitingStore(new File(blocker, "t.tsv"), new File(blocker, "p.tsv"), w2::add);
        ro.put(A, e("k", 1), 40);
        ro.flush();
        int first = w2.size();
        ro.flush();
        expect(first >= 1 && w2.size() > first, "hard: a failed save is tried again on the next flush: " + w2.size());
        // half done (timers ok, profiles not) still counts as unsaved
        File okDir = new File(d, "okdir");
        okDir.mkdirs();
        List<String> w3 = new ArrayList<>();
        WaitingStore half = new WaitingStore(new File(okDir, "t.tsv"), new File(blocker, "p.tsv"), w3::add);
        half.put(A, e("k", 1), 40);
        half.flush();
        int h1 = w3.size();
        half.flush();
        expect(h1 >= 1 && w3.size() > h1, "hard: if only one of the two files saves, it is retried");
        WaitingStore half2 = new WaitingStore(new File(blocker, "t.tsv"), new File(okDir, "p2.tsv"), w3::add);
        half2.put(A, e("k", 1), 40);
        int before = w3.size();
        half2.flush();
        half2.flush();
        expect(w3.size() >= before + 2, "hard: ...whichever of the two fails");

        // an unreadable file is reported
        File asDir = new File(d, "asdir");
        asDir.mkdirs();
        List<String> w4 = new ArrayList<>();
        new WaitingStore(asDir, new File(d, "nothing"), w4::add).load();
        expect(w4.size() == 1 && w4.get(0).contains("Could not read asdir"), "hard: an unreadable file is reported: " + w4);

        // load forgets what was in memory, profiles too
        WaitingStore mem = new WaitingStore(new File(d, "nope1"), new File(d, "nope2"), warns::add);
        mem.profile(A).discordId = "123456789012345678";
        mem.load();
        expect(mem.peekProfile(A) == null && mem.linkedCount() == 0, "hard: load drops in-memory profiles");

        // a profile line that is the right length but nonsense
        Files.write(pf.toPath(), List.of(B + "\t\t1\t0\tNaN\t0\t1\tBob"), StandardCharsets.UTF_8);
        List<String> w5 = new ArrayList<>();
        WaitingStore badp = new WaitingStore(new File(d, "none"), pf, w5::add);
        badp.load();
        expect(badp.peekProfile(B) == null && w5.size() == 1 && w5.get(0).contains(" 1 "), "hard: a nonsense profile line is skipped and counted: " + w5);
    }

    // ------------------------------------------------------------------ NexusGateProbe

    static void probe() {
        List<String> warns = new ArrayList<>();
        FakeGate gate = new FakeGate();
        NexusGateProbe p = new NexusGateProbe(() -> gate, warns::add);
        UUID in = UUID.randomUUID();
        UUID out = UUID.randomUUID();
        expect(!p.waiting(in), "probe: nobody pending");
        gate.ids.add(in);
        expect(p.waiting(in), "probe: pending player waits (the list's class is not public, so the probe must open it up)");
        expect(!p.waiting(out), "probe: other player does not");
        gate.enabled = false;
        expect(!p.waiting(in), "probe: disabled NexusGate -> nobody waits");
        gate.enabled = true;
        expect(p.waiting(in), "probe: enabled again");
        expect(warns.isEmpty(), "probe: no warnings while it works");

        NexusGateProbe none = new NexusGateProbe(() -> null, warns::add);
        expect(!none.waiting(in) && warns.isEmpty(), "probe: NexusGate not installed -> false, quietly");

        NexusGateProbe nullManager = new NexusGateProbe(() -> new NullManagerGate(), warns::add);
        expect(!nullManager.waiting(in) && warns.isEmpty(), "probe: NexusGate without a pending list yet -> false, quietly");

        NexusGateProbe odd = new NexusGateProbe(() -> new NoFieldGate(), warns::add);
        expect(!odd.waiting(in), "probe: different NexusGate build -> false");
        expect(warns.size() == 1 && warns.get(0).contains("NoSuchFieldException"), "probe: one warning naming the problem: " + warns);
        expect(!odd.waiting(in) && warns.size() == 1, "probe: warns only once");

        FakeGate first = new FakeGate();
        FakeGate second = new FakeGate();
        Plugin[] current = {first};
        NexusGateProbe sw = new NexusGateProbe(() -> current[0], warns::add);
        expect(!sw.waiting(in), "probe: first plugin instance, nobody pending");
        second.ids.add(in);
        current[0] = second;
        expect(sw.waiting(in), "probe: follows a reloaded NexusGate (new instance)");
        expect(LoginGate.NONE.waiting(in) == false, "login gate NONE");
    }
}
