package com.nexuscraft.nexuswaiting;

import java.util.List;
import java.util.regex.Pattern;

/** Builds the Discord webhook message and checks what goes into it. */
final class DiscordMessage {
    private DiscordMessage() {
    }

    static final int MAX_CONTENT = 300;
    static final int LABELS_SHOWN = 3;
    private static final Pattern USER_ID = Pattern.compile("\\d{17,20}");
    /** https://discord.com/api/webhooks/{id}/{token}, also discordapp.com, ptb. and canary., optionally with ?wait=true style options. */
    private static final Pattern WEBHOOK = Pattern.compile(
            "https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[A-Za-z0-9._-]+(?:\\?[A-Za-z0-9=&_.-]*)?");
    private static final Pattern CODES = Pattern.compile("[&\u00a7][0-9a-fk-orA-FK-OR]");

    /** A Discord user id is 17 to 20 digits. */
    static boolean validUserId(String id) {
        return id != null && USER_ID.matcher(id).matches();
    }

    /** Only real Discord webhook addresses are ever posted to. */
    static boolean validWebhook(String url) {
        return url != null && WEBHOOK.matcher(url.trim()).matches();
    }

    /** No colour codes, no line breaks, no @ and no angle brackets: nothing in it can turn into a mention. */
    static String plain(String s) {
        if (s == null) {
            return "";
        }
        String t = CODES.matcher(s).replaceAll("");
        t = t.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        t = t.replace("@", "").replace("<", "\u2039").replace(">", "\u203a");
        return t.trim();
    }

    /**
     * The words of the message: {@code template} with {player}, {server} and {what} filled in. {what} lists up to
     * three labels, then "+N more". Cut to {@link #MAX_CONTENT} characters (leaving room for the mention).
     */
    static String compose(String template, String player, String server, List<String> labels) {
        StringBuilder what = new StringBuilder();
        int shown = Math.min(labels.size(), LABELS_SHOWN);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                what.append("; ");
            }
            what.append(plain(labels.get(i)));
        }
        if (labels.size() > shown) {
            what.append("; +").append(labels.size() - shown).append(" more");
        }
        String text = template.replace("{player}", plain(player)).replace("{server}", plain(server)).replace("{what}", what);
        text = plain(text);
        int room = MAX_CONTENT - 30;
        if (text.length() > room) {
            text = text.substring(0, room - 1) + "\u2026";
        }
        return text;
    }

    /** The JSON body: mentions only the one linked user, nothing else (no @everyone, no roles). */
    static String json(String userId, String text, String username) {
        StringBuilder b = new StringBuilder(160);
        b.append("{\"content\":\"<@").append(userId).append("> ").append(escape(text)).append('"');
        if (username != null && !username.isBlank()) {
            b.append(",\"username\":\"").append(escape(plain(username))).append('"');
        }
        b.append(",\"allowed_mentions\":{\"parse\":[],\"users\":[\"").append(userId).append("\"]}}");
        return b.toString();
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
