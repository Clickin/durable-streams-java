package io.github.clickin.server.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueryStringTest {

    @Test
    void parseDecodesKeysAndValues() {
        Map<String, String> parsed = QueryString.parse(URI.create("http://localhost/streams/x?offset=%2D1&live=long-poll"));
        assertThat(parsed).containsEntry("offset", "-1");
        assertThat(parsed).containsEntry("live", "long-poll");
    }

    @Test
    void parseHandlesMissingValueAsEmpty() {
        Map<String, String> parsed = QueryString.parse(URI.create("http://localhost/streams/x?offset"));
        assertThat(parsed).containsEntry("offset", "");
    }

    @Test
    void hasDuplicateDetectsRepeatedKeys() {
        assertThat(QueryString.hasDuplicate(URI.create("http://localhost/streams/x?offset=-1&offset=0"), "offset"))
                .isTrue();
        assertThat(QueryString.hasDuplicate(URI.create("http://localhost/streams/x?offset=-1&live=long-poll"), "offset"))
                .isFalse();
    }

    @Test
    void hasDuplicateDecodesKeysBeforeComparing() {
        assertThat(QueryString.hasDuplicate(URI.create("http://localhost/streams/x?off%73et=-1&offset=0"), "offset"))
                .isTrue();
    }
}
