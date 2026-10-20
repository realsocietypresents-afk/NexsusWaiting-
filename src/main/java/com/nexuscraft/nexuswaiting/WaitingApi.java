package com.nexuscraft.nexuswaiting;

import java.util.UUID;

/**
 * What other plugins use to put something on a player's "waiting for you" list. Registered with
 * Bukkit's services manager; plugins that must still load without NexusWaiting reach it by name
 * (see the README for a copy-paste bridge). Safe to call from the main thread only.
 */
public interface WaitingApi {

    /**
     * Puts (or replaces) one timer. {@code key} identifies it for this player (use something stable such as
     * {@code "hatchling:" + mobUuid}); setting the same key again with the same ready time changes nothing,
     * with a new ready time it counts as a new timer. {@code label} is one short sentence saying what is ready,
     * for example "Fluffy has grown up". Ignored when the player already has too many timers.
     */
    void set(UUID player, String key, String label, long readyAtMillis);

    /** Removes a timer (it was collected, cancelled, or no longer matters). Does nothing if there is none. */
    void clear(UUID player, String key);

    /** Is there a timer with this key (ready or not)? */
    boolean has(UUID player, String key);
}
