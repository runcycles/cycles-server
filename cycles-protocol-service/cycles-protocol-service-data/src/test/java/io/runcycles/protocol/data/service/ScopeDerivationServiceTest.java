package io.runcycles.protocol.data.service;

import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScopeDerivationService")
class ScopeDerivationServiceTest {

    private ScopeDerivationService service;

    @BeforeEach
    void setUp() {
        service = new ScopeDerivationService();
    }

    @Nested
    @DisplayName("buildScopePath")
    class BuildScopePath {

        @Test
        void shouldBuildPathForTenantOnly() {
            Subject subject = new Subject();
            subject.setTenant("acme");

            String path = service.buildScopePath(subject);

            assertThat(path).isEqualTo("tenant:acme");
        }

        @Test
        void shouldBuildPathForTenantAndWorkspace() {
            Subject subject = new Subject();
            subject.setTenant("acme");
            subject.setWorkspace("dev");

            String path = service.buildScopePath(subject);

            assertThat(path).isEqualTo("tenant:acme/workspace:dev");
        }

        @Test
        void shouldFillGapsWithDefault() {
            Subject subject = new Subject();
            subject.setTenant("acme");
            subject.setAgent("summarizer-v2");

            String path = service.buildScopePath(subject);

            assertThat(path).isEqualTo("tenant:acme/workspace:default/app:default/workflow:default/agent:summarizer-v2");
        }

        @Test
        void shouldBuildFullHierarchy() {
            Subject subject = new Subject();
            subject.setTenant("acme");
            subject.setWorkspace("dev");
            subject.setApp("chatbot");
            subject.setWorkflow("summarize");
            subject.setAgent("agent-1");
            subject.setToolset("tools-v2");

            String path = service.buildScopePath(subject);

            assertThat(path).isEqualTo("tenant:acme/workspace:dev/app:chatbot/workflow:summarize/agent:agent-1/toolset:tools-v2");
        }

        @Test
        void shouldLowercaseValues() {
            Subject subject = new Subject();
            subject.setTenant("ACME");
            subject.setWorkspace("DEV");

            String path = service.buildScopePath(subject);

            assertThat(path).isEqualTo("tenant:acme/workspace:dev");
        }

        @Test
        void shouldThrowForEmptySubject() {
            Subject subject = new Subject();

            assertThatThrownBy(() -> service.buildScopePath(subject))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least tenant defined");
        }
    }

    @Nested
    @DisplayName("deriveScopes")
    class DeriveScopes {

        @Test
        void shouldDeriveScopesForTenantOnly() {
            Subject subject = new Subject();
            subject.setTenant("acme");

            List<String> scopes = service.deriveScopes(subject);

            assertThat(scopes).containsExactly("tenant:acme");
        }

        @Test
        void shouldDeriveCumulativeScopes() {
            Subject subject = new Subject();
            subject.setTenant("acme");
            subject.setWorkspace("dev");
            subject.setApp("chatbot");

            List<String> scopes = service.deriveScopes(subject);

            assertThat(scopes).containsExactly(
                    "tenant:acme",
                    "tenant:acme/workspace:dev",
                    "tenant:acme/workspace:dev/app:chatbot"
            );
        }

        @Test
        void shouldDeriveScopesWithGaps() {
            Subject subject = new Subject();
            subject.setTenant("acme");
            subject.setAgent("summarizer-v2");

            List<String> scopes = service.deriveScopes(subject);

            assertThat(scopes).containsExactly(
                    "tenant:acme",
                    "tenant:acme/workspace:default",
                    "tenant:acme/workspace:default/app:default",
                    "tenant:acme/workspace:default/app:default/workflow:default",
                    "tenant:acme/workspace:default/app:default/workflow:default/agent:summarizer-v2"
            );
        }

        @Test
        void shouldDeriveFullHierarchyScopes() {
            Subject subject = new Subject();
            subject.setTenant("acme");
            subject.setWorkspace("dev");
            subject.setApp("chatbot");
            subject.setWorkflow("summarize");
            subject.setAgent("agent-1");
            subject.setToolset("tools-v2");

            List<String> scopes = service.deriveScopes(subject);

            assertThat(scopes).hasSize(6);
            assertThat(scopes.get(0)).isEqualTo("tenant:acme");
            assertThat(scopes.get(5)).isEqualTo("tenant:acme/workspace:dev/app:chatbot/workflow:summarize/agent:agent-1/toolset:tools-v2");
        }
    }

    @Nested
    @DisplayName("buildBudgetKey")
    class BuildBudgetKey {

        @Test
        void shouldBuildBudgetKeyForTenant() {
            Subject subject = new Subject();
            subject.setTenant("acme");

            String key = service.buildBudgetKey(subject, Enums.UnitEnum.TOKENS);

            assertThat(key).isEqualTo("budget:tenant:acme:TOKENS");
        }

        @Test
        void shouldBuildBudgetKeyForComplexScope() {
            Subject subject = new Subject();
            subject.setTenant("acme");
            subject.setWorkspace("dev");

            String key = service.buildBudgetKey(subject, Enums.UnitEnum.USD_MICROCENTS);

            assertThat(key).isEqualTo("budget:tenant:acme/workspace:dev:USD_MICROCENTS");
        }

        @Test
        void shouldBuildBudgetKeyWithDifferentUnits() {
            Subject subject = new Subject();
            subject.setTenant("acme");

            assertThat(service.buildBudgetKey(subject, Enums.UnitEnum.TOKENS))
                    .isEqualTo("budget:tenant:acme:TOKENS");
            assertThat(service.buildBudgetKey(subject, Enums.UnitEnum.USD_MICROCENTS))
                    .isEqualTo("budget:tenant:acme:USD_MICROCENTS");
            assertThat(service.buildBudgetKey(subject, Enums.UnitEnum.CREDITS))
                    .isEqualTo("budget:tenant:acme:CREDITS");
            assertThat(service.buildBudgetKey(subject, Enums.UnitEnum.RISK_POINTS))
                    .isEqualTo("budget:tenant:acme:RISK_POINTS");
        }
    }
}
