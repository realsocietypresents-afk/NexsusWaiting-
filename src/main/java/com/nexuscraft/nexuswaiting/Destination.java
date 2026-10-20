package com.nexuscraft.nexuswaiting;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A place a scout can be sent: how long it takes, what it can find, and the little stories it comes back with. */
final class Destination {
    final String id;
    final String name;
    final Material icon;
    final int minutes;
    final List<String> lore;
    final int rolls;
    final List<Reward> rewards;
    final int jackpotPercent;
    final List<Reward> jackpot;
    final List<String> stories;

    Destination(String id, String name, Material icon, int minutes, List<String> lore, int rolls, List<Reward> rewards,
                int jackpotPercent, List<Reward> jackpot, List<String> stories) {
        if (rewards.isEmpty()) {
            throw new IllegalArgumentException("a destination needs at least one reward");
        }
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.minutes = minutes;
        this.lore = new ArrayList<>(lore);
        this.rolls = rolls;
        this.rewards = new ArrayList<>(rewards);
        this.jackpotPercent = Math.max(0, Math.min(100, jackpotPercent));
        this.jackpot = new ArrayList<>(jackpot);
        this.stories = new ArrayList<>(stories);
    }

    long millis() {
        return minutes * 60_000L;
    }

    /** What this trip brings back: {@code rolls} finds picked by weight, maybe one jackpot find, and a story. */
    Haul roll(Random random) {
        Haul haul = new Haul();
        for (int i = 0; i < rolls; i++) {
            add(haul, pick(rewards, random), random);
        }
        if (!jackpot.isEmpty() && random.nextInt(100) < jackpotPercent) {
            haul.jackpot = true;
            add(haul, pick(jackpot, random), random);
        }
        if (!stories.isEmpty()) {
            haul.story = stories.get(random.nextInt(stories.size()));
        }
        return haul;
    }

    private static void add(Haul haul, Reward r, Random random) {
        if (r.isCommand()) {
            haul.commands.add(r.command);
            return;
        }
        int amount = r.min + random.nextInt(r.max - r.min + 1);
        haul.items.merge(r.item, amount, Integer::sum);
    }

    /** One reward from a non-empty list, chosen by weight. */
    private static Reward pick(List<Reward> pool, Random random) {
        int total = 0;
        for (Reward r : pool) {
            total += r.weight;
        }
        int roll = random.nextInt(total);
        Reward chosen = pool.get(0);
        for (Reward r : pool) {
            chosen = r;
            roll -= r.weight;
            if (roll < 0) {
                break;
            }
        }
        return chosen;
    }
}
