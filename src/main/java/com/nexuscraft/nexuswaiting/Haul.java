package com.nexuscraft.nexuswaiting;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** What an errand brings back. */
final class Haul {
    final Map<Material, Integer> items = new LinkedHashMap<>();
    final List<String> commands = new ArrayList<>();
    boolean jackpot;
    String story = "";

    /** Inventory slots needed to hold every item, in whole stacks. */
    int slotsNeeded(ToIntFunction<Material> maxStack) {
        int slots = 0;
        for (Map.Entry<Material, Integer> e : items.entrySet()) {
            int stack = Math.max(1, maxStack.applyAsInt(e.getKey()));
            slots += (e.getValue() + stack - 1) / stack;
        }
        return slots;
    }

    boolean isEmpty() {
        return items.isEmpty() && commands.isEmpty();
    }
}
