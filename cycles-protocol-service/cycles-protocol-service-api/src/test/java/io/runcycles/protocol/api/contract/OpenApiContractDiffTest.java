package io.runcycles.protocol.api.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.openapitools.openapidiff.core.OpenApiCompare;
import org.openapitools.openapidiff.core.model.ChangedOpenApi;
import org.openapitools.openapidiff.core.model.ChangedOperation;
import org.openapitools.openapidiff.core.model.DiffResult;
import org.openapitools.openapidiff.core.model.Endpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 2 contract check — STRUCTURAL DIFF.
 *
 * <p>Diffs SpringDoc's generated {@code /api-docs} output (the server's
 * self-declared OpenAPI surface) against the authoritative protocol spec
 * on {@code cycles-protocol@main}. Catches the drift class that runtime
 * validation can't see: endpoints present in the spec but not implemented
 * on the server (or vice versa), and operation-level signature drift on
 * shared paths.
 *
 * <p>Semantic mapping to openapi-diff:
 * <ul>
 *   <li>OLD = pinned spec (authoritative)</li>
 *   <li>NEW = server {@code /api-docs} (reality)</li>
 *   <li>{@code missingEndpoints} = in spec, missing on server → FAIL</li>
 *   <li>{@code newEndpoints} = on server, missing from spec → FAIL
 *       (undocumented surface)</li>
 *   <li>{@code changedOperations} with INCOMPATIBLE core = breaking drift → FAIL</li>
 * </ul>
 *
 * <p>Respects the same enablement gate as the runtime validator
 * ({@link ContractValidationConfig#validationEnabled()}). Skipped when
 * gate is OFF (offline dev).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // JedisPool is mocked below but property placeholders still resolve
        // at context-build time, so supply defaults.
        "redis.host=localhost",
        "redis.port=6379",
        "redis.password=",
        "cycles.expiry.initial-delay-ms=999999999",
        "webhook.secret.encryption-key="
})
class OpenApiContractDiffTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private JedisPool jedisPool;

    @Test
    @EnabledIf(value = "io.runcycles.protocol.api.contract.ContractValidationConfig#validationEnabled",
               disabledReason = "contract.validation.enabled=false — skipping structural diff")
    void serverApiDocs_structurallyMatchesPinnedSpec() throws Exception {
        String serverJson = fetchApiDocs();

        String specYaml = ContractSpecLoader.loadSpec();
        ChangedOpenApi diff = OpenApiCompare.fromContents(specYaml, serverJson);

        List<Endpoint> missing = diff.getMissingEndpoints();
        List<Endpoint> extra = diff.getNewEndpoints();
        // Only fail on INCOMPATIBLE (spec-breaking) operation-level changes.
        // SpringDoc's generated OpenAPI won't exactly match hand-written spec
        // at deep $ref / description / styling level. Deep schema drift is
        // caught by the runtime validator (Phase 1); this test's unique value
        // is structural: missing endpoints, extra endpoints, and breaking
        // signature changes on shared endpoints.
        List<ChangedOperation> breakingOps = diff.getChangedOperations().stream()
                .filter(op -> op.isCoreChanged() == DiffResult.INCOMPATIBLE)
                .collect(Collectors.toList());

        assertAll("structural diff between spec (cycles-protocol@main) and server /api-docs",
                () -> assertTrue(missing.isEmpty(),
                        "Spec operations MISSING on server (server doesn't implement these): " + formatEndpoints(missing)),
                () -> assertTrue(extra.isEmpty(),
                        "Server operations NOT in spec (undocumented protocol surface): " + formatEndpoints(extra)),
                () -> assertTrue(breakingOps.isEmpty(),
                        "Operations with BREAKING signature divergence (spec requires X, server does Y): " + formatChanged(breakingOps))
        );
    }

    private String fetchApiDocs() throws Exception {
        for (String path : new String[]{"/api-docs", "/v3/api-docs"}) {
            var result = mockMvc.perform(get(path)).andReturn();
            if (result.getResponse().getStatus() == 200) {
                return result.getResponse().getContentAsString();
            }
        }
        throw new AssertionError("SpringDoc /api-docs and /v3/api-docs both unavailable");
    }

    private static String formatEndpoints(List<Endpoint> endpoints) {
        if (endpoints.isEmpty()) return "[]";
        return endpoints.stream()
                .map(e -> e.getMethod() + " " + e.getPathUrl())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String formatChanged(List<ChangedOperation> ops) {
        if (ops.isEmpty()) return "[]";
        return ops.stream()
                .map(o -> o.getHttpMethod() + " " + o.getPathUrl())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
