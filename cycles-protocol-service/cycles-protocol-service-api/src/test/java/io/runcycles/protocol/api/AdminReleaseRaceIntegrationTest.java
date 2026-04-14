package io.runcycles.protocol.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races the v0.1.25.8 admin-driven release against a tenant-driven commit on the same
 * reservation. Both paths end up in the same Lua atomic (`commit.lua` or `release.lua`),
 * so exactly one of them should win and the loser must see `RESERVATION_FINALIZED` (409).
 *
 * The failure mode this guards against is a regression where both calls appear to succeed
 * — that would mean the state-machine is being violated and a reservation was both
 * committed and released, producing an inconsistent audit trail and potentially double-
 * charging or double-releasing budget.
 *
 * The admin path is gated by {@code AdminApiKeyAuthenticationFilter} and requires the
 * {@code X-Admin-API-Key} header to match the {@code admin.api-key} property. We inject a
 * test value via {@link TestPropertySource}.
 */
@TestPropertySource(properties = "admin.api-key=race-test-admin-key")
@DisplayName("Admin-release vs agent-commit race")
class AdminReleaseRaceIntegrationTest extends BaseIntegrationTest {

    private static final String ADMIN_KEY = "race-test-admin-key";

    /** Repeat to surface scheduler variance — a single iteration often lets one thread
     *  finish before the other even starts the HTTP call. */
    @RepeatedTest(20)
    @DisplayName("exactly one of {commit, admin-release} wins, loser sees RESERVATION_FINALIZED")
    void concurrentCommitAndAdminReleaseProduceExactlyOneWinner() throws Exception {
        // Arrange: fresh reservation; ample budget so commit is not budget-denied.
        String reservationId = createReservationAndGetId(TENANT_A, API_KEY_SECRET_A, 1_000);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(2);

        Callable<ResponseEntity<Map>> commitCall = () -> {
            start.await();
            return post("/v1/reservations/" + reservationId + "/commit",
                    API_KEY_SECRET_A, commitBody(500));
        };
        Callable<ResponseEntity<Map>> adminReleaseCall = () -> {
            start.await();
            return adminRelease(reservationId);
        };

        Future<ResponseEntity<Map>> commitFuture = exec.submit(commitCall);
        Future<ResponseEntity<Map>> releaseFuture = exec.submit(adminReleaseCall);

        start.countDown();

        ResponseEntity<Map> commitResp = commitFuture.get(10, TimeUnit.SECONDS);
        ResponseEntity<Map> releaseResp = releaseFuture.get(10, TimeUnit.SECONDS);
        exec.shutdown();

        // Assert: exactly one success, the other is a 409 RESERVATION_FINALIZED.
        boolean commitOk = commitResp.getStatusCode().is2xxSuccessful();
        boolean releaseOk = releaseResp.getStatusCode().is2xxSuccessful();

        assertThat(commitOk ^ releaseOk)
                .as("exactly one of commit/admin-release should win; commit=%s (%s), release=%s (%s)",
                        commitResp.getStatusCode(), commitResp.getBody(),
                        releaseResp.getStatusCode(), releaseResp.getBody())
                .isTrue();

        if (!commitOk) {
            assertThat(commitResp.getStatusCode().value())
                    .as("commit loser must be 409; body=%s", commitResp.getBody())
                    .isEqualTo(409);
            assertThat(commitResp.getBody().get("error"))
                    .isEqualTo("RESERVATION_FINALIZED");
        }
        if (!releaseOk) {
            assertThat(releaseResp.getStatusCode().value())
                    .as("admin-release loser must be 409; body=%s", releaseResp.getBody())
                    .isEqualTo(409);
            assertThat(releaseResp.getBody().get("error"))
                    .isEqualTo("RESERVATION_FINALIZED");
        }

        // Post-hoc: reservation hash is in a single terminal state consistent with the winner.
        Map<String, String> res = getReservationStateFromRedis(reservationId);
        String state = res.get("state");
        if (commitOk) {
            assertThat(state).isEqualTo("COMMITTED");
            assertThat(res.get("released_amount"))
                    .as("winner=commit — released_amount must be absent")
                    .isNull();
        } else {
            assertThat(state).isEqualTo("RELEASED");
            assertThat(res.get("charged_amount"))
                    .as("winner=admin-release — charged_amount must be absent")
                    .isNull();
        }
    }

    /**
     * Admin-release call: same endpoint as tenant release but gated by X-Admin-API-Key.
     * The tenant API key is intentionally NOT sent — dual-auth allows admin-only access
     * to this endpoint when the admin header is present.
     */
    private ResponseEntity<Map> adminRelease(String reservationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Admin-API-Key", ADMIN_KEY);
        return restTemplate.exchange(
                baseUrl() + "/v1/reservations/" + reservationId + "/release",
                HttpMethod.POST,
                new HttpEntity<>(releaseBody(), headers),
                Map.class);
    }
}
