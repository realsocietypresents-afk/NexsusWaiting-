package com.nexuscraft.nexuswaiting;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Asks NexusGate whether a player is still waiting to log in. NexusGate has no public API for it, so
 * this reaches its pending-login list by name; if NexusGate is missing or built differently the answer
 * is simply "not waiting" (the menu then relies on its join delay) and one warning is logged.
 */
final class NexusGateProbe implements LoginGate {

    private final Supplier<Plugin> gate;
    private final Consumer<String> warn;
    private Plugin cachedFor;
    private Object manager;
    private Method isPending;
    private boolean broken;

    NexusGateProbe(Supplier<Plugin> gate, Consumer<String> warn) {
        this.gate = gate;
        this.warn = warn;
    }

    @Override
    public boolean waiting(UUID id) {
        if (broken) {
            return false;
        }
        try {
            Plugin p = gate.get();
            if (p == null || !p.isEnabled()) {
                return false;
            }
            if (p != cachedFor || manager == null) {
                Field f = p.getClass().getDeclaredField("pendingAuthManager");
                f.setAccessible(true);
                manager = f.get(p);
                if (manager == null) {
                    return false;
                }
                isPending = manager.getClass().getMethod("isPending", UUID.class);
                isPending.setAccessible(true);
                cachedFor = p;
            }
            return Boolean.TRUE.equals(isPending.invoke(manager, id));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            broken = true;
            warn.accept("Could not ask NexusGate who is still logging in (" + ex.getClass().getSimpleName()
                    + "); the popup will rely on its join delay instead.");
            return false;
        }
    }
}
