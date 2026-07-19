package com.flowscope.ingest;

import com.flowscope.lang.LanguageRegistry;
import com.flowscope.lang.LanguageSpec;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Single-pass, symlink-safe directory walker. Maps file extensions to languages
 * via the {@link LanguageRegistry}, skips noisy build/vcs directories, and
 * records (rather than aborts on) unreadable entries.
 */
public class DefaultFileWalker implements FileWalker {

    /** Directory names skipped wholesale (in addition to any dot-prefixed dir). */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "target", "vendor", "dist", "build", "out",
            ".idea", ".gradle", ".mvn", "__pycache__", ".next");

    private final LanguageRegistry registry;

    public DefaultFileWalker(LanguageRegistry registry) {
        this.registry = registry;
    }

    @Override
    public WalkResult walk(Path sourceDirectory) {
        Path root = sourceDirectory.toAbsolutePath().normalize();
        return walkAll(List.of(new ProjectRoots.Root(root, root.getFileName().toString())));
    }

    @Override
    public WalkResult walkAll(List<ProjectRoots.Root> roots) {
        List<SourceFile> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ProjectRoots.Root r : roots) {
            walkOne(r.path().toAbsolutePath().normalize(), r.module(), files, warnings);
        }
        return new WalkResult(files, warnings);
    }

    /** Walk one root; relative paths are prefixed with {@code module/} so IDs stay
     *  unique across modules. */
    private void walkOne(Path root, String module, List<SourceFile> files, List<String> warnings) {
        try {
            // No FOLLOW_LINKS: symlinks to directories are reported to visitFile
            // (not entered), preventing circular traversal (Requirement 1.8).
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Skip symlinks (a symlink to a directory lands here since we do
                    // not follow links) and anything that is not a regular file.
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String ext = extensionOf(file.getFileName().toString());
                    Optional<LanguageSpec> spec = registry.forExtension(ext);
                    if (spec.isPresent()) {
                        String rel = module + "/"
                                + root.relativize(file).toString().replace('\\', '/');
                        files.add(new SourceFile(
                                file.toAbsolutePath().normalize(), rel, spec.get().languageId()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Requirement 1.3: skip unreadable entries, record a warning, continue.
                    warnings.add("skipped (unreadable): " + file + " — " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("walk error: " + e.getMessage());
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        return fileName.substring(dot).toLowerCase(Locale.ROOT);
    }
}
