package com.nexuscraft.nexuswaiting;

import org.bukkit.Material;

/** One possible find on an errand: some of an item, or a console command (for example to pay out money). */
final class Reward {
    /** Never more than this many of one item in one find, so a haul always fits in whole stacks. */
    static final int MAX_AMOUNT = 64;

    final Material item;
    final String command;
    final int min;
    final int max;
    final int weight;

    Reward(Material item, String command, int min, int max, int weight) {
        this.item = item;
        this.command = command == null ? "" : command;
        int lo = Math.max(1, Math.min(min, MAX_AMOUNT));
        this.min = lo;
        this.max = Math.max(lo, Math.min(max, MAX_AMOUNT));
        this.weight = Math.max(1, weight);
    }

    boolean isCommand() {
        return item == null;
    }
}
