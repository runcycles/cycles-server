package io.runcycles.protocol.data.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopePathMatcherTest {

    @Test
    void matchesOnlyExactSlashBoundedSegments() {
        assertThat(ScopePathMatcher.hasExactSegment(
            "tenant:acme/workspace:dev", "tenant:acme")).isTrue();
        assertThat(ScopePathMatcher.hasExactSegment(
            "tenant:acme/workspace:dev/app:web", "workspace:dev")).isTrue();
        assertThat(ScopePathMatcher.hasExactSegment(
            "tenant:acme/workspace:dev", "workspace:dev")).isTrue();
        assertThat(ScopePathMatcher.hasExactSegment(
            "tenant:acme-corp/workspace:dev", "tenant:acme")).isFalse();
        assertThat(ScopePathMatcher.hasExactSegment(
            "tenant:acme/workspace:dev", "tenant:ac")).isFalse();
        assertThat(ScopePathMatcher.hasExactSegment(
            "tenant:acme/workspace:dev", "app:web")).isFalse();
        assertThat(ScopePathMatcher.hasExactSegment(
            "tenant:acme", "tenant:acme")).isTrue();
        assertThat(ScopePathMatcher.hasExactSegment(null, "tenant:acme")).isFalse();
        assertThat(ScopePathMatcher.hasExactSegment("tenant:acme", null)).isFalse();
    }

    @Test
    void preservesFirstOccurrenceBehavior() {
        assertThat(ScopePathMatcher.hasExactSegment(
            "xworkspace:dev/workspace:dev", "workspace:dev")).isFalse();
    }
}
