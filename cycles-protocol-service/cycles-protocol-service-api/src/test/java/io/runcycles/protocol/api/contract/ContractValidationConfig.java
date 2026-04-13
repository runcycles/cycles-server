package io.runcycles.protocol.api.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.OpenApiMatchers;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
    private static List<SpecOperation> cachedOperations;

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
                // As of cycles-protocol@208a7be (#34), all 9 runtime operations
                // document 400. Strict enforcement: any response whose status code
                // isn't listed in the spec for that operation now fails the build.
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

    /** Spec operations (method + path template). Parsed once per JVM. */
    public static synchronized List<SpecOperation> specOperations() {
        if (cachedOperations != null) return cachedOperations;
        ParseOptions opts = new ParseOptions();
        opts.setResolve(false);
        OpenAPI api = new OpenAPIV3Parser()
                .readContents(ContractSpecLoader.loadSpec(), null, opts)
                .getOpenAPI();
        List<SpecOperation> ops = new ArrayList<>();
        if (api != null && api.getPaths() != null) {
            api.getPaths().forEach((pathTemplate, item) -> {
                if (item.getGet() != null)    ops.add(new SpecOperation("GET",    pathTemplate));
                if (item.getPost() != null)   ops.add(new SpecOperation("POST",   pathTemplate));
                if (item.getPut() != null)    ops.add(new SpecOperation("PUT",    pathTemplate));
                if (item.getPatch() != null)  ops.add(new SpecOperation("PATCH",  pathTemplate));
                if (item.getDelete() != null) ops.add(new SpecOperation("DELETE", pathTemplate));
            });
        }
        cachedOperations = List.copyOf(ops);
        return cachedOperations;
    }

    /** Records coverage by resolving the concrete URI to its spec template. */
    public static void recordCoverage(String method, String path) {
        for (SpecOperation op : specOperations()) {
            if (op.method.equalsIgnoreCase(method) && op.matches(path)) {
                SpecCoverageCollector.record(op.method, op.pathTemplate);
                return;
            }
        }
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
            // Always record coverage, regardless of response body (204s, empty 401s count).
            recordCoverage(result.getRequest().getMethod(), path);
            String contentType = result.getResponse().getContentType();
            if (contentType == null || !contentType.contains("json")) return;
            byte[] body = result.getResponse().getContentAsByteArray();
            if (body == null || body.length == 0) return;
            full.match(result);
        };
        return builder -> builder.alwaysExpect(onSpecPaths);
    }

    /** Method + path-template pair with a compiled regex for URI matching. */
    public static final class SpecOperation {
        public final String method;
        public final String pathTemplate;
        private final Pattern regex;

        SpecOperation(String method, String pathTemplate) {
            this.method = method;
            this.pathTemplate = pathTemplate;
            // /v1/foo/{bar}/baz -> ^/v1/foo/[^/]+/baz$
            String re = "^" + pathTemplate.replaceAll("\\{[^}]+}", "[^/]+") + "$";
            this.regex = Pattern.compile(re);
        }

        public boolean matches(String actualPath) {
            return regex.matcher(actualPath).matches();
        }
    }
}
