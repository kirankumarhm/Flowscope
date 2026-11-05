package com.flowscope.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowscope.exception.ApiExceptions;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AllowedRootsTest {

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        AllowedRoots.configure("");
    }

    @Test
    void emptyListAllowsEverything() {
        AllowedRoots.configure("");
        assertThatCode(() -> AllowedRoots.check(Path.of("/"))).doesNotThrowAnyException();
    }

    @Test
    void pathsOutsideTheRootsAreRejected() throws Exception {
        Path allowed = Files.createDirectories(tmp.resolve("allowed/app"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        AllowedRoots.configure(tmp.resolve("allowed").toString());
        assertThatCode(() -> AllowedRoots.check(allowed)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AllowedRoots.check(other))
                .isInstanceOf(ApiExceptions.InvalidPathException.class);
    }

    @Test
    void symlinksCannotEscapeTheRoots() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("root"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path link = Files.createSymbolicLink(root.resolve("link"), outside);
        AllowedRoots.configure(root.toString());
        assertThatThrownBy(() -> AllowedRoots.check(link))
                .isInstanceOf(ApiExceptions.InvalidPathException.class);
    }
}
