package com.flowscope.util;

import com.flowscope.exception.ApiExceptions;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Turns a user-supplied {@code path} parameter into an absolute, normalized path. */
public final class PathParams {

    private PathParams() {
    }

    /**
     * Trim the value and drop one pair of matching quotes around it, as added by
     * Finder's "Copy as Pathname" or a shell ({@code '/a b/c'}, {@code "/a b/c"}),
     * then resolve it to an absolute, normalized path.
     *
     * @throws ApiExceptions.MissingPathException when nothing is left
     */
    public static Path resolve(String raw) {
        String p = unquote(raw);
        if (p.isEmpty()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        return Paths.get(p).toAbsolutePath().normalize();
    }

    /** The trimmed value without one pair of matching surrounding quotes. */
    public static String unquote(String raw) {
        if (raw == null) {
            return "";
        }
        String p = raw.trim();
        if (p.length() >= 2) {
            char first = p.charAt(0);
            char last = p.charAt(p.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                p = p.substring(1, p.length() - 1).trim();
            }
        }
        return p;
    }
}
