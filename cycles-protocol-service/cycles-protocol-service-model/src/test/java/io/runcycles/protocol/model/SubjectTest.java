package io.runcycles.protocol.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Subject")
class SubjectTest {

    @Test
    void shouldPassValidationWithTenantOnly() {
        Subject subject = new Subject();
        subject.setTenant("acme");

        assertThat(subject.hasAtLeastOneStandardField()).isTrue();
    }

    @Test
    void shouldPassValidationWithWorkspaceOnly() {
        Subject subject = new Subject();
        subject.setWorkspace("dev");

        assertThat(subject.hasAtLeastOneStandardField()).isTrue();
    }

    @Test
    void shouldPassValidationWithAppOnly() {
        Subject subject = new Subject();
        subject.setApp("chatbot");

        assertThat(subject.hasAtLeastOneStandardField()).isTrue();
    }

    @Test
    void shouldPassValidationWithAgentOnly() {
        Subject subject = new Subject();
        subject.setAgent("summarizer");

        assertThat(subject.hasAtLeastOneStandardField()).isTrue();
    }

    @Test
    void shouldPassValidationWithToolsetOnly() {
        Subject subject = new Subject();
        subject.setToolset("tools-v2");

        assertThat(subject.hasAtLeastOneStandardField()).isTrue();
    }

    @Test
    void shouldFailValidationWithOnlyDimensions() {
        Subject subject = new Subject();
        subject.setDimensions(Map.of("env", "prod"));

        assertThat(subject.hasAtLeastOneStandardField()).isFalse();
    }

    @Test
    void shouldFailValidationWhenEmpty() {
        Subject subject = new Subject();

        assertThat(subject.hasAtLeastOneStandardField()).isFalse();
    }

    @Test
    void shouldFailValidationWithBlankFields() {
        Subject subject = new Subject();
        subject.setTenant("   ");
        subject.setWorkspace("");

        assertThat(subject.hasAtLeastOneStandardField()).isFalse();
    }

    @Test
    void shouldPassWithMultipleFields() {
        Subject subject = new Subject();
        subject.setTenant("acme");
        subject.setWorkspace("dev");
        subject.setAgent("bot-1");

        assertThat(subject.hasAtLeastOneStandardField()).isTrue();
    }
}
