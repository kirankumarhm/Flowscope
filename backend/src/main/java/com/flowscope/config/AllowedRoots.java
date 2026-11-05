package com.flowscope.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.flowscope.exception.ApiExceptions;

/**
 * Optional allow-list of directories FlowScope may analyze
 * ({@code flowscope.allowed-roots}, comma-separated). Every entry point that turns
 * a user-supplied {@code path} into a directory calls {@link #check(Path)}.
 * An empty list allows any readable path, which keeps the default behaviour.
 *
 * <p>The list is held statically so the plain (non-bean) builders and the bean
 * services share one policy without threading it through every constructor.
 */
@Component
public class AllowedRoots {

    private static volatile List<Path> roots = List.of();

    public AllowedRoots(@Value("${flowscope.allowed-roots:}") String configured) {
        configure(configured);
    }

    /** Replace the allow-list (also used by tests). Blank means "allow everything". */
    public static void configure(String configured) {
        List<Path> parsed = new ArrayList<>();
        if (configured != null) {
            Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(s -> parsed.add(canonical(Paths.get(s))));
        }
        roots = List.copyOf(parsed);
    }

    /**
     * Reject {@code dir} unless it lies inside one of the allowed roots. Symlinks
     * are resolved first so a link cannot point outside the allow-list.
     *
     * @throws ApiExceptions.InvalidPathException when the path is outside every root
     */
    public static void check(Path dir) {
        List<Path> allowed = roots;
        if (allowed.isEmpty()) {
            return;
        }
        Path real = canonical(dir);
        for (Path root : allowed) {
            if (real.startsWith(root)) {
                return;
            }
        }
        throw new ApiExceptions.InvalidPathException(
                "path is outside flowscope.allowed-roots: " + dir);
    }

    /** True when {@code dir} may be read (always true when no roots are configured). */
    public static boolean allows(Path dir) {
        try {
            check(dir);
            return true;
        } catch (ApiExceptions.InvalidPathException e) {
            return false;
        }
    }

    private static Path canonical(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return Files.exists(abs) ? abs.toRealPath() : abs;
        } catch (IOException e) {
            return abs;
        }
    }
}
