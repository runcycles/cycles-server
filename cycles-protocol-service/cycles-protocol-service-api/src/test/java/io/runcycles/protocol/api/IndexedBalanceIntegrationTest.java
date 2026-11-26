package io.runcycles.protocol.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "cycles.balances.use-governance-index=true")
class IndexedBalanceIntegrationTest extends BaseIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldPageThroughTenantBudgetsUsingTheIndex() {
        String[] scopes = {
                "tenant:" + TENANT_A,
                "tenant:" + TENANT_A + "/app:alpha",
                "tenant:" + TENANT_A + "/app:beta"};
        try (Jedis jedis = jedisPool.getResource()) {
            seedScopeBudget(jedis, scopes[1], "TOKENS", 1000, 0);
            seedScopeBudget(jedis, scopes[2], "TOKENS", 2000, 0);
            for (String scope : scopes) {
                jedis.sadd("budgets:" + TENANT_A, "budget:" + scope + ":TOKENS");
            }
            String broken = "budget:" + scopes[0] + "/app:bad:TOKENS";
            jedis.set(broken, "wrong-type");
            jedis.sadd("budgets:" + TENANT_A, broken);
            // An index entry whose budget hash is gone must be skipped, not fail the page.
            jedis.sadd("budgets:" + TENANT_A, "budget:tenant:" + TENANT_A + "/app:deleted:TOKENS");
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<Map> resp = get("/v1/balances?tenant=" + TENANT_A + "&limit=1"
                    + (cursor != null ? "&cursor=" + cursor : ""), API_KEY_SECRET_A);
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            List<Map<String, Object>> balances = (List<Map<String, Object>>) resp.getBody().get("balances");
            for (Map<String, Object> balance : balances) {
                seen.add((String) balance.get("scope_path"));
            }
            cursor = (String) resp.getBody().get("next_cursor");
            assertThat(resp.getBody().get("has_more")).isEqualTo(cursor != null);
            if (cursor != null) assertThat(cursor).startsWith("bi1.");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactlyInAnyOrder(scopes);
        assertThat(seen).doesNotHaveDuplicates();
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexNeverLeaksAnotherTenantsBudget() {
        try (Jedis jedis = jedisPool.getResource()) {
            // A poisoned index entry pointing at tenant B's budget must still be filtered out.
            jedis.sadd("budgets:" + TENANT_A, "budget:tenant:" + TENANT_A + ":TOKENS",
                    "budget:tenant:" + TENANT_B + ":TOKENS");
        }

        ResponseEntity<Map> resp = get("/v1/balances?tenant=" + TENANT_A, API_KEY_SECRET_A);

        List<Map<String, Object>> balances = (List<Map<String, Object>>) resp.getBody().get("balances");
        assertThat(balances).extracting(b -> b.get("scope_path")).containsExactly("tenant:" + TENANT_A);
    }
}
