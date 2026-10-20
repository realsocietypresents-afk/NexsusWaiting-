package com.nexuscraft.nexuswaiting;

/** What we keep about a player apart from their timers: their Discord link and how often they were pinged. */
final class Profile {
    /** Discord user id (digits only) or "" when not linked. */
    String discordId = "";
    boolean enabled = true;
    /** Does the list pop up by itself when they log in (the server's own setting permitting)? */
    boolean popup = true;
    long lastPingAt;
    /** Which UTC day {@link #pingCount} is for. */
    long pingDay;
    int pingCount;
    String name = "";

    boolean linked() {
        return !discordId.isEmpty();
    }
}
