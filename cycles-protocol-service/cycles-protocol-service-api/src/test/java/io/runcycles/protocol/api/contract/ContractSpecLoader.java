package io.runcycles.protocol.api.contract;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;

/**
 * Loads the authoritative protocol OpenAPI spec from cycles-protocol for
 * contract tests. Caches per-build to {@code target/contract/spec.yaml} so
 * repeated test runs within the same build don't re-download.
 *
 * <p><b>Tracking policy:</b> this loader tracks {@code cycles-protocol@main}.
 * Any spec change merged to main will be picked up on the next CI run (cache
 * expires after {@link #CACHE_TTL}; CI workspaces are always fresh), so spec
 * drift is caught as soon as the spec changes — not when somebody remembers
 * to bump a pin. The trade-off: a breaking spec change on {@code main} can
 * red-light unrelated server PRs. That's deliberate — the server is expected
 * to be spec-compliant at all times, and drift should surface immediately.
 *
 * <p>{@link #LAST_KNOWN_GOOD_SHA} records the most recent spec commit this
 * loader was explicitly verified against. It's informational only (not used
 * at runtime) — useful for forensics if a spec change breaks the build, and
 * as a reference point for "what the server was compliant with when this
 * file was last touched."
 *
 * <p>Refresh policy: if the cached file exists and was written within the
 * last {@link #CACHE_TTL}, use it; otherwise re-fetch.
 *
 * <p>Override via system property {@code contract.spec.url} for local spec
 * development (e.g. {@code -Dcontract.spec.url=file:///path/to/spec.yaml})
 * or to temporarily pin to a specific SHA while investigating a failure.
 */
public final class ContractSpecLoader {

    /**
     * Most recent spec commit this loader was explicitly verified against.
     * Informational only; runtime always fetches {@code main}. Update when
     * making changes to this loader or to contract-test behavior so the
     * reference stays fresh.
     */
    public static final String LAST_KNOWN_GOOD_SHA = "208a7be51837b35d58e993d26a18f6eecba26d24";

    public static final String DEFAULT_SPEC_URL =
            "https://raw.githubusercontent.com/runcycles/cycles-protocol/main/cycles-protocol-v0.yaml";
    public static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Path CACHE_PATH = Path.of("target", "contract", "spec.yaml");

    private static String cached;

    private ContractSpecLoader() {}

    /** Returns the YAML content as a String. Blocks on first call per JVM. */
    public static synchronized String loadSpec() {
        if (cached != null) return cached;
        try {
            String specUrl = System.getProperty("contract.spec.url");
            if (specUrl != null) {
                cached = fetch(specUrl);
                return cached;
            }
            if (isCacheFresh()) {
                cached = Files.readString(CACHE_PATH);
                return cached;
            }
            cached = fetch(DEFAULT_SPEC_URL);
            Files.createDirectories(CACHE_PATH.getParent());
            Files.writeString(CACHE_PATH, cached,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return cached;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load contract spec. If offline, provide -Dcontract.spec.url=file:///path/to/spec.yaml",
                e);
        }
    }

    private static boolean isCacheFresh() throws IOException {
        if (!Files.exists(CACHE_PATH)) return false;
        Instant mtime = Files.getLastModifiedTime(CACHE_PATH).toInstant();
        return Instant.now().isBefore(mtime.plus(CACHE_TTL));
    }

    private static String fetch(String url) throws IOException, InterruptedException {
        if (url.startsWith("file:")) {
            return Files.readString(Path.of(URI.create(url)));
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Spec fetch " + url + " returned HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
