package com.nexuscraft.nexuswaiting;

import java.util.Locale;

/** When the list pops up by itself at login. */
enum PopupMode {
    /** Only when something is ready (the default). */
    WHEN_READY,
    /** At every login, ready or not. */
    ALWAYS,
    /** Never by itself; players type /waiting. */
    NEVER;

    static PopupMode parse(String s, PopupMode fallback) {
        if (s == null) {
            return fallback;
        }
        switch (s.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "when-ready":
                return WHEN_READY;
            case "always":
                return ALWAYS;
            case "never":
            case "off":
                return NEVER;
            default:
                return fallback;
        }
    }

    /** Should the list pop up for a player who has {@code ready} things ready? */
    boolean shouldPopup(int ready) {
        switch (this) {
            case ALWAYS:
                return true;
            case WHEN_READY:
                return ready > 0;
            default:
                return false;
        }
    }
}
