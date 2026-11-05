package com.flowscope.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowscope.exception.ApiExceptions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathParamsTest {

    @Test
    void quotesAndSpacesAroundAPathAreRemoved() {
        Path expected = Path.of("/tmp/a b/c");
        assertThat(PathParams.resolve("'/tmp/a b/c'")).isEqualTo(expected);
        assertThat(PathParams.resolve("\"/tmp/a b/c\"")).isEqualTo(expected);
        assertThat(PathParams.resolve("  '/tmp/a b/c'  ")).isEqualTo(expected);
        assertThat(PathParams.resolve("/tmp/a b/c")).isEqualTo(expected);
    }

    @Test
    void mismatchedOrInnerQuotesAreKept() {
        assertThat(PathParams.unquote("'/tmp/x\"")).isEqualTo("'/tmp/x\"");
        assertThat(PathParams.unquote("/tmp/it's")).isEqualTo("/tmp/it's");
    }

    @Test
    void quotesAroundNothingAreAMissingPath() {
        assertThatThrownBy(() -> PathParams.resolve("''"))
                .isInstanceOf(ApiExceptions.MissingPathException.class);
        assertThatThrownBy(() -> PathParams.resolve("  "))
                .isInstanceOf(ApiExceptions.MissingPathException.class);
    }
}
