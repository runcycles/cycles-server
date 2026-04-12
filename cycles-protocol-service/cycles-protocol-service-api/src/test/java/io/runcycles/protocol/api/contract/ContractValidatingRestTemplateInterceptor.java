package io.runcycles.protocol.api.contract;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Contract-validating interceptor for {@code TestRestTemplate} used by
 * {@code *IntegrationTest} classes. Captures every request/response pair that
 * hits a spec-defined path, runs it through the shared
 * {@link ContractValidationConfig#sharedValidator()}, and fails the test if
 * the response body doesn't conform to the pinned {@code cycles-protocol-v0.yaml}.
 *
 * <p>Coverage: integration tests execute the real controller → service →
 * Redis → Lua path, producing the widest variety of response shapes in the
 * build. Wrapping {@code TestRestTemplate} here means every path they
 * exercise — happy, error, edge — is automatically validated.
 *
 * <p>Respects {@link ContractValidationConfig#validationEnabled()}.
 *
 * <p>Buffers the response body in memory so downstream assertions can still
 * read it (the original {@code InputStream} is one-shot).
 */
public class ContractValidatingRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                         byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (!ContractValidationConfig.validationEnabled()) {
            return response;
        }
        String path = request.getURI().getPath();
        if (!ContractValidationConfig.isSpecPath(path)) {
            return response;
        }

        byte[] responseBody = StreamUtils.copyToByteArray(response.getBody());
        ClientHttpResponse buffered = new BufferedResponse(response, responseBody);

        String contentType = response.getHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("json") || responseBody.length == 0) {
            return buffered;
        }

        OpenApiInteractionValidator validator = ContractValidationConfig.sharedValidator();
        Request apiRequest = toApiRequest(request, body);
        SimpleResponse apiResponse = SimpleResponse.Builder
                .status(response.getStatusCode().value())
                .withContentType(contentType)
                .withBody(responseBody)
                .build();
        ValidationReport report = validator.validate(apiRequest, apiResponse);
        if (report.hasErrors()) {
            throw new AssertionError("Contract validation failed for "
                    + request.getMethod() + " " + path + "\n"
                    + report.getMessages().stream()
                            .filter(m -> m.getLevel() == ValidationReport.Level.ERROR)
                            .map(Object::toString)
                            .collect(Collectors.joining("\n")));
        }
        return buffered;
    }

    private static Request toApiRequest(HttpRequest request, byte[] body) {
        URI uri = request.getURI();
        Request.Method method = Request.Method.valueOf(request.getMethod().name());
        SimpleRequest.Builder builder = new SimpleRequest.Builder(method, uri.getPath());
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) builder.withQueryParam(pair.substring(0, eq), pair.substring(eq + 1));
                else builder.withQueryParam(pair, "");
            }
        }
        for (Map.Entry<String, List<String>> h : request.getHeaders().entrySet()) {
            for (String v : h.getValue()) builder.withHeader(h.getKey(), v);
        }
        if (body != null && body.length > 0) {
            builder.withBody(new String(body, StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    /** Wraps a response with a pre-read body so callers can still consume it. */
    private static class BufferedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override public org.springframework.http.HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public void close() { delegate.close(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public org.springframework.http.HttpHeaders getHeaders() { return delegate.getHeaders(); }
    }
}
