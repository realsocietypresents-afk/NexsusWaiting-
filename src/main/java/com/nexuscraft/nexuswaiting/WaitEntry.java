package com.nexuscraft.nexuswaiting;

/** One thing a player is waiting for: it becomes ready at a real-world moment, whether anyone is online or not. */
final class WaitEntry {

    static final String SOURCE_API = "api";
    static final String SOURCE_ERRAND = "errand";
    static final int MAX_KEY = 64;
    static final int MAX_LABEL = 80;

    final String key;
    String label;
    long readyAt;
    /** True once the player has been told (in game or on Discord) that this is ready. */
    boolean notified;
    String source;
    /** Free text the source keeps with the timer (an errand keeps its destination id here). */
    String data;

    WaitEntry(String key, String label, long readyAt, String source, String data) {
        this.key = cleanKey(key);
        this.label = cleanLabel(label);
        this.readyAt = readyAt;
        this.source = source == null || source.isBlank() ? SOURCE_API : cleanKey(source);
        this.data = data == null ? "" : flat(data);
    }

    boolean ready(long now) {
        return readyAt <= now;
    }

    boolean isErrand() {
        return SOURCE_ERRAND.equals(source);
    }

    static String cleanKey(String s) {
        String k = s == null ? "" : s.trim().replaceAll("\\s+", "_");
        k = flat(k);
        return k.length() > MAX_KEY ? k.substring(0, MAX_KEY) : k;
    }

    static String cleanLabel(String s) {
        String l = s == null ? "" : flat(s.replace('§', '&')).trim();
        if (l.length() > MAX_LABEL) {
            l = l.substring(0, MAX_LABEL).trim();
        }
        return l;
    }

    /** One line, no tabs: the files are tab separated. */
    static String flat(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
