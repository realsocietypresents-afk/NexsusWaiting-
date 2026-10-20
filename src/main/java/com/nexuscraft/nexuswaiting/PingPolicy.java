package com.nexuscraft.nexuswaiting;

import java.util.ArrayDeque;

/** The rules for how often one person may be pinged on Discord, and the whole server per hour. */
final class PingPolicy {
    private PingPolicy() {
    }

    static final long DAY = 86_400_000L;
    static final long HOUR = 3_600_000L;

    static long dayOf(long now) {
        return Math.floorDiv(now, DAY);
    }

    /** May this player be pinged now? At least {@code minMinutes} since the last ping, and under {@code maxPerDay} today. */
    static boolean allowed(Profile p, long now, int minMinutes, int maxPerDay) {
        if (p.lastPingAt > 0 && now - p.lastPingAt < minMinutes * 60_000L) {
            return false;
        }
        return !(p.pingDay == dayOf(now) && p.pingCount >= maxPerDay);
    }

    static void record(Profile p, long now) {
        long day = dayOf(now);
        if (p.pingDay != day) {
            p.pingDay = day;
            p.pingCount = 0;
        }
        p.pingCount++;
        p.lastPingAt = now;
    }

    /** Whole-server limit: drops pings older than an hour from {@code recent}, then says whether there is room. */
    static boolean serverHasRoom(ArrayDeque<Long> recent, long now, int maxPerHour) {
        while (!recent.isEmpty() && now - recent.peekFirst() >= HOUR) {
            recent.pollFirst();
        }
        return recent.size() < maxPerHour;
    }
}
