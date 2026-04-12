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
 * For integration tests ({@code TestRestTemplate}-based) use
 * {@link ContractValidatingRestTemplateInterceptor} — it shares the same
 * validator via {@link #sharedValidator()}.
 *
 * <p><b>Enablement gate:</b> ON by default. Disable with
 * {@code -Dcontract.validation.enabled=false} (or env
 * {@code CONTRACT_VALIDATION_ENABLED=false}) for offline / air-gapped
 * dev where the cycles-protocol fetch would fail.
 *
 * <p>The spec is fetched by {@link ContractSpecLoader} from a pinned
 * commit SHA in cycles-protocol and cached to {@code target/contract/}.
 */
@TestConfiguration
public class ContractValidationConfig {

    private static OpenApiInteractionValidator cachedValidator;

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

    /**
     * Shared validator reused across MockMvc (Phase 1 unit) and
     * {@code TestRestTemplate} (integration) contract checks. Built once
     * per JVM to avoid reparsing the spec. Noise filters are applied
     * centrally here so both harnesses enforce identical semantics.
     */
    public static synchronized OpenApiInteractionValidator sharedValidator() {
        if (cachedValidator != null) return cachedValidator;
        LevelResolver levels = LevelResolver.create()
                // Request-side is entirely noisy: integration/unit tests deliberately
                // send malformed input to exercise 400/410/etc. error paths. Bean
                // Validation (@Valid/@Size/@Min on DTOs) is the production gate,
                // and DtoConstraintContractTest verifies required fields statically.
                // So: IGNORE every request-side rule. The validator's value here
                // is enforcing RESPONSE shape only.
                .withLevel("validation.request", ValidationReport.Level.IGNORE)
                // Responses with undocumented statuses (e.g. 400 on malformed JSON
                // for paths where spec only documents 401/404) are allowed to pass.
                // TODO flip to ERROR after cycles-protocol adds 400 responses to all paths.
                .withLevel("validation.response.status.unknown", ValidationReport.Level.IGNORE)
                .build();
        cachedValidator = OpenApiInteractionValidator
                .createForInlineApiSpecification(ContractSpecLoader.loadSpec())
                .withLevelResolver(levels)
                .build();
        return cachedValidator;
    }

    /** True when the URI is owned by the spec (not infrastructure). */
    public static boolean isSpecPath(String path) {
        return !(path.startsWith("/api-docs")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/actuator"));
    }

    @Bean
    public MockMvcBuilderCustomizer contractValidatingCustomizer() {
        if (!validationEnabled()) {
            return builder -> { };
        }
        ResultMatcher full = new OpenApiMatchers().isValid(sharedValidator());
        // Validate every JSON response on spec-defined paths. Skip:
        //   - infrastructure paths not in the spec
        //   - responses with no JSON body (204, empty 401, non-JSON content types)
        ResultMatcher onSpecPaths = result -> {
            String path = result.getRequest().getRequestURI();
            if (!isSpecPath(path)) return;
            String contentType = result.getResponse().getContentType();
            if (contentType == null || !contentType.contains("json")) return;
            byte[] body = result.getResponse().getContentAsByteArray();
            if (body == null || body.length == 0) return;
            full.match(result);
        };
        return builder -> builder.alwaysExpect(onSpecPaths);
    }
}
