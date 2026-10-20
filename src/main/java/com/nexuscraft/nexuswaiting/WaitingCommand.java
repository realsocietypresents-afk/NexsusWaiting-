package com.nexuscraft.nexuswaiting;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;

/** /waiting: open the list, claim a scout, link Discord; for staff also add or clear timers (command blocks too). */
final class WaitingCommand implements CommandExecutor, TabCompleter {

    static final String ADMIN = "nexuswaiting.admin";
    /** A timer set by command can be at most this far away: one year. */
    static final double MAX_MINUTES = 525_600;

    private final WaitingService service;
    private final Runnable reload;
    private final LongSupplier clock;

    WaitingCommand(WaitingService service, Runnable reload, LongSupplier clock) {
        this.service = service;
        this.reload = reload;
        this.clock = clock;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open":
                return open(sender);
            case "list":
                return list(sender);
            case "claim":
                return claim(sender, args);
            case "popup":
                return popup(sender, args);
            case "discord":
                return discord(sender, args);
            case "set":
                return set(sender, args);
            case "clear":
                return clear(sender, args);
            case "test":
                return testTimer(sender, args);
            case "unlink":
                return unlink(sender, args);
            case "status":
                return status(sender);
            case "reload":
                return reloadCmd(sender);
            default:
                sender.sendMessage(ColorText.translate("&7Usage: &f/" + label + " [list|claim|popup on|off|discord link <id>|unlink|on|off|test]"
                        + (sender.hasPermission(ADMIN) ? "&7  Staff: &fset|clear|test|unlink|status|reload" : "")));
                return true;
        }
    }

    private boolean open(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can open the list. Staff: /waiting set <player> <key> <minutes> <label...>");
            return true;
        }
        service.open(p);
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            return open(sender);
        }
        long now = clock.getAsLong();
        List<WaitEntry> all = service.store().all(p.getUniqueId());
        if (all.isEmpty()) {
            service.say(p, service.cfg().msg("empty"));
            return true;
        }
        all.sort(java.util.Comparator.comparingLong((WaitEntry e) -> e.readyAt));
        for (WaitEntry e : all) {
            p.sendMessage(ColorText.translate((e.ready(now) ? "&a[ready] &f" : "&e[" + TimeText.left(e.readyAt - now) + "] &f") + ColorText.strip(e.label)));
        }
        return true;
    }

    private boolean claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players have scouts.");
            return true;
        }
        if (!service.cfg().errandsEnabled) {
            service.say(p, service.cfg().msg("errand-off"));
            return true;
        }
        int slot;
        if (args.length >= 2) {
            try {
                slot = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                service.say(p, "&7Usage: &f/waiting claim [scout number]");
                return true;
            }
            service.claim(p, slot);
            return true;
        }
        long now = clock.getAsLong();
        for (int s = 1; s <= service.slotsFor(p); s++) {
            WaitEntry e = service.store().get(p.getUniqueId(), WaitingService.errandKey(s));
            if (e != null && e.ready(now)) {
                service.claim(p, s);
                return true;
            }
        }
        service.say(p, service.cfg().msg("errand-none"));
        return true;
    }

    private boolean popup(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players have a popup setting.");
            return true;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            service.say(p, "&7Usage: &f/waiting popup on|off");
            return true;
        }
        service.setPopup(p, args[1].equalsIgnoreCase("on"));
        return true;
    }

    private boolean discord(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can link Discord.");
            return true;
        }
        String sub = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link":
                if (args.length < 3) {
                    service.say(p, service.cfg().msg("discord-bad-id"));
                } else {
                    service.link(p, args[2]);
                }
                break;
            case "unlink":
                service.say(p, service.cfg().msg(service.unlink(p.getUniqueId()) ? "discord-unlinked" : "discord-not-linked"));
                break;
            case "on":
                service.setDiscordEnabled(p, true);
                break;
            case "off":
                service.setDiscordEnabled(p, false);
                break;
            case "test":
                service.test(p);
                break;
            default:
                service.discordStatus(p);
                break;
        }
        return true;
    }

    // ------------------------------------------------------------------ staff

    private boolean set(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return denied(sender);
        }
        if (args.length < 4) {
            sender.sendMessage(ColorText.translate("&7Usage: &f/waiting set <player|@p> <key> <minutes> <label...>"));
            return true;
        }
        double minutes;
        try {
            minutes = Double.parseDouble(args[3]);
        } catch (NumberFormatException ex) {
            minutes = Double.NaN;
        }
        if (Double.isNaN(minutes) || minutes < 0 || minutes > MAX_MINUTES) {
            sender.sendMessage(ColorText.translate("&cMinutes must be a number from 0 to " + (long) MAX_MINUTES + " (0 = ready now)."));
            return true;
        }
        List<UUID> targets = resolve(sender, args[1]);
        if (targets.isEmpty()) {
            sender.sendMessage(ColorText.translate("&c" + args[1] + " is not a player I know."));
            return true;
        }
        String label = args.length > 4 ? String.join(" ", Arrays.copyOfRange(args, 4, args.length)) : args[2];
        long ready = clock.getAsLong() + Math.round(minutes * 60_000.0);
        int done = 0;
        for (UUID id : targets) {
            if (service.set(id, args[2], label, ready)) {
                done++;
            }
        }
        sender.sendMessage(ColorText.translate(done == 0 ? "&cThat player already has the maximum number of timers."
                : "&aTimer '" + WaitEntry.cleanKey(args[2]) + "' set for " + done + " player(s), ready in " + TimeText.left(Math.round(minutes * 60_000.0)) + "."));
        return true;
    }

    private boolean clear(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return denied(sender);
        }
        if (args.length < 3) {
            sender.sendMessage(ColorText.translate("&7Usage: &f/waiting clear <player|@p> <key>"));
            return true;
        }
        int done = 0;
        for (UUID id : resolve(sender, args[1])) {
            if (service.clear(id, args[2])) {
                done++;
            }
        }
        sender.sendMessage(ColorText.translate(done == 0 ? "&7Nothing to clear." : "&aCleared " + done + " timer(s)."));
        return true;
    }

    private boolean testTimer(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return denied(sender);
        }
        if (args.length < 2) {
            sender.sendMessage(ColorText.translate("&7Usage: &f/waiting test <player>  (makes a timer that is ready right now)"));
            return true;
        }
        List<UUID> targets = resolve(sender, args[1]);
        if (targets.isEmpty()) {
            sender.sendMessage(ColorText.translate("&c" + args[1] + " is not a player I know."));
            return true;
        }
        for (UUID id : targets) {
            service.set(id, "test", "Test timer from staff", clock.getAsLong());
        }
        sender.sendMessage(ColorText.translate("&aTest timer set; it is announced at the next check (a few seconds)."));
        return true;
    }

    private boolean unlink(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            return denied(sender);
        }
        if (args.length < 2) {
            sender.sendMessage(ColorText.translate("&7Usage: &f/waiting unlink <player>"));
            return true;
        }
        int done = 0;
        for (UUID id : resolve(sender, args[1])) {
            if (service.unlink(id)) {
                done++;
            }
        }
        sender.sendMessage(ColorText.translate(done == 0 ? "&7That player had no Discord linked." : "&aDiscord unlinked."));
        return true;
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            return denied(sender);
        }
        WaitingConfig c = service.cfg();
        sender.sendMessage(ColorText.translate("&d" + service.store().timerCount() + "&7 timers, &d" + service.store().linkedCount()
                + "&7 players linked to Discord. Discord pings: &f" + (c.discordReady() ? "ready" : (c.discordEnabled ? "enabled but the address is not usable" : "off"))
                + "&7. Errands: &f" + (c.errandsEnabled ? c.destinations.size() + " places" : "off") + "&7."));
        return true;
    }

    private boolean reloadCmd(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            return denied(sender);
        }
        reload.run();
        sender.sendMessage(ColorText.translate("&aNexusWaiting reloaded."));
        for (String w : service.cfg().warnings) {
            sender.sendMessage(ColorText.translate("&e" + w));
        }
        return true;
    }

    /** Who a staff command means: a selector such as @p (command blocks), an online player, or someone who has played here. */
    private List<UUID> resolve(CommandSender sender, String who) {
        List<UUID> out = new ArrayList<>();
        if (who.startsWith("@")) {
            try {
                for (Entity e : Bukkit.selectEntities(sender, who)) {
                    if (e instanceof Player p) {
                        out.add(p.getUniqueId());
                    }
                }
            } catch (IllegalArgumentException ex) {
                // not a selector this server understands: nothing selected
            }
            return out;
        }
        Player online = Bukkit.getPlayerExact(who);
        if (online != null) {
            out.add(online.getUniqueId());
            return out;
        }
        OfflinePlayer o = Bukkit.getOfflinePlayer(who);
        if (o.hasPlayedBefore()) {
            out.add(o.getUniqueId());
        }
        return out;
    }

    private static boolean denied(CommandSender sender) {
        sender.sendMessage(ColorText.translate("&cYou cannot do that."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission(ADMIN);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("open", "list", "claim", "popup", "discord"));
            if (admin) {
                subs.addAll(List.of("set", "clear", "test", "unlink", "status", "reload"));
            }
            addMatches(out, subs, args[0]);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("discord")) {
            addMatches(out, List.of("link", "unlink", "on", "off", "test", "status"), args[1]);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("popup")) {
            addMatches(out, List.of("on", "off"), args[1]);
        } else if (args.length == 2 && admin && List.of("set", "clear", "test", "unlink").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            addMatches(out, names, args[1]);
        }
        return out;
    }

    private static void addMatches(List<String> out, List<String> options, String typed) {
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))) {
                out.add(s);
            }
        }
    }
}
