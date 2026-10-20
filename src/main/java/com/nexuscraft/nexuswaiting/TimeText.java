package com.nexuscraft.nexuswaiting;

/** "2h 5m"-style durations. Always rounds up, so a timer that is not ready never shows "0m". */
final class TimeText {
    private TimeText() {
    }

    static String left(long millis) {
        if (millis <= 0) {
            return "ready";
        }
        long totalMinutes = (millis + 59_999L) / 60_000L;
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        return minutes + "m";
    }
}
