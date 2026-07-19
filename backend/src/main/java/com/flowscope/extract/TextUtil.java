package com.flowscope.extract;

import java.util.regex.Pattern;

/** Small text helpers for producing clean, bounded node labels. */
public final class TextUtil {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextUtil() {
    }

    /**
     * Collapse all whitespace runs to a single space, trim, and truncate to
     * {@code max} characters (replacing the final character with '…' when longer).
     * Satisfies the CFG label formatting rules (Requirement 4.9 / Property 9).
     */
    public static String oneLine(String s, int max) {
        return truncate(normalize(s), max);
    }

    /** Collapse all whitespace runs to a single space and trim — no truncation. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return WHITESPACE.matcher(s).replaceAll(" ").trim();
    }

    /** Truncate to {@code max} chars, replacing the last char with '…' when longer. */
    public static String truncate(String s, int max) {
        if (s.length() > max) {
            return s.substring(0, max - 1) + "…";
        }
        return s;
    }
}
