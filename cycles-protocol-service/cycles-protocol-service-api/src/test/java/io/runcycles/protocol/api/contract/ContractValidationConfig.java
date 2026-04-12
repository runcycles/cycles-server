package io.runcycles.protocol.api.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Test configuration that attaches a Swagger Request Validator matcher to
 * every MockMvc request. When enabled, any response whose body doesn't
 * conform to the pinned protocol spec fails the test.
 *
 * <p>Import from controller tests via {@code @Import(ContractValidationConfig.class)}.
 *
 * <p><b>Enablement gate:</b> ON by default. Disable with
 * {@code -Dcontract.validation.enabled=false} (or env
 * {@code CONTRACT_VALIDATION_ENABLED=false}) for offline / air-gapped
 * dev where the cycles-protocol@main fetch would fail.
 *
 * <p>When the gate is OFF the customizer is a no-op — no spec fetch, no
 * matcher attached — so tests are unaffected by network or cache state.
 *
 * <p>The spec itself is fetched by {@link ContractSpecLoader} from
 * cycles-protocol@main ({@code cycles-protocol-v0.yaml}) and cached to
 * {@code target/contract/}.
 */
@TestConfiguration
public class ContractValidationConfig {

    /**
     * Defaults to true. Disable for offline dev with
     * -Dcontract.validation.enabled=false or env CONTRACT_VALIDATION_ENABLED=false.
     */
    public static boolean validationEnabled() {
        String prop = System.getProperty("contract.validation.enabled");
        if (prop != null) return Boolean.parseBoolean(prop);
        String env = System.getenv("CONTRACT_VALIDATION_ENABLED");
        if (env != null) return Boolean.parseBoolean(env);
        return true;
    }

    @Bean
    public MockMvcBuilderCustomizer contractValidatingCustomizer() {
        if (!validationEnabled()) {
            return builder -> { };
        }
        // Request-side validation is noisy: tests deliberately send bad inputs to
        // exercise error handling. We enforce RESPONSE shape only — 2xx bodies
        // match success schemas, 4xx/5xx JSON bodies match ErrorResponse.
        LevelResolver levels = LevelResolver.create()
                .withLevel("validation.request.parameter.schema.maximum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.minimum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.invalidJson", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.parameter.schema.enum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.missing", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.required", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.invalidJson", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.enum", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.pattern", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.anyOf", ValidationReport.Level.IGNORE)
                .withLevel("validation.request.body.schema.oneOf", ValidationReport.Level.IGNORE)
                // Allow responses with statuses not documented in the spec
                // (e.g. 400 on malformed JSON) to pass through unvalidated.
                .withLevel("validation.response.status.unknown", ValidationReport.Level.IGNORE)
                .build();
        OpenApiInteractionValidator validator = OpenApiInteractionValidator
                .createForInlineApiSpecification(ContractSpecLoader.loadSpec())
                .withLevelResolver(levels)
                .build();
        ResultMatcher full = new OpenApiMatchers().isValid(validator);
        // Validate every JSON response on spec-defined paths. Skip:
        //   - infrastructure paths not in the spec (/api-docs, /v3/api-docs,
        //     /swagger-ui, /actuator)
        //   - responses with no JSON body (204, empty 401, non-JSON content types)
        ResultMatcher onSpecPaths = result -> {
            String path = result.getRequest().getRequestURI();
            if (path.startsWith("/api-docs")
                    || path.startsWith("/v3/api-docs")
                    || path.startsWith("/swagger-ui")
                    || path.startsWith("/actuator")) {
                return;
            }
            String contentType = result.getResponse().getContentType();
            if (contentType == null || !contentType.contains("json")) return;
            byte[] body = result.getResponse().getContentAsByteArray();
            if (body == null || body.length == 0) return;
            full.match(result);
        };
        return builder -> builder.alwaysExpect(onSpecPaths);
    }
}
