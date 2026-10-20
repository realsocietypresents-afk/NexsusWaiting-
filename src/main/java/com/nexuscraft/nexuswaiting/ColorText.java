package com.nexuscraft.nexuswaiting;

/** Turns &-colour codes into section signs. */
final class ColorText {
    private ColorText() {
    }

    static String translate(String s) {
        return s == null ? "" : s.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    /** The text with every colour code (either &x or section-sign form) taken out. */
    static String strip(String s) {
        return s == null ? "" : s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
    }
}
