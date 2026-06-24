package io.runcycles.protocol.data.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogSanitizer")
class LogSanitizerTest {

    @Test
    void nullStaysNull() {
        assertThat(LogSanitizer.sanitize(null)).isNull();
    }

    @Test
    void cleanValueUnchanged() {
        assertThat(LogSanitizer.sanitize("tenant:acme/app:web")).isEqualTo("tenant:acme/app:web");
    }

    @Test
    @DisplayName("CR, LF, and CRLF are all flattened to spaces (blocks log forging)")
    void newlinesFlattened() {
        assertThat(LogSanitizer.sanitize("res_x\nfake INFO line")).isEqualTo("res_x fake INFO line");
        assertThat(LogSanitizer.sanitize("a\rb")).isEqualTo("a b");
        assertThat(LogSanitizer.sanitize("a\r\nb")).isEqualTo("a  b");
    }

    @Test
    void nonStringRenderedViaToString() {
        assertThat(LogSanitizer.sanitize(42)).isEqualTo("42");
    }
}
