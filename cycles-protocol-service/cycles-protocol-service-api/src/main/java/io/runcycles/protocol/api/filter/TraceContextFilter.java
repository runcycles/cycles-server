package io.runcycles.protocol.api.filter;

import io.runcycles.protocol.data.util.TraceContext;
import io.runcycles.protocol.data.util.TraceIdGenerator;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cycles Protocol v0.1.25 (revision 2026-04-18) — CORRELATION AND TRACING.
 *
 * <p>Extracts a W3C Trace Context-compatible {@code trace_id} from inbound
 * {@code traceparent} or {@code X-Cycles-Trace-Id} headers, or generates one.
 * Also captures the inbound trace-flags byte and whether the inbound
 * {@code traceparent} was valid, so the events service can preserve the
 * upstream sampling decision on outbound webhook {@code traceparent} headers
 * (admin spec v0.1.25.28 {@code WebhookDelivery.trace_flags} +
 * {@code traceparent_inbound_valid}).
 *
 * <p>Ordered before {@link RequestIdFilter} so downstream filters and controllers
 * can read the trace context from the request.
 */
@Component
@Order(0)
public class TraceContextFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Cycles-Trace-Id";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACE_ID_ATTRIBUTE = "cyclesTraceId";
    public static final String TRACE_FLAGS_ATTRIBUTE = "cyclesTraceFlags";
    public static final String TRACE_INBOUND_W3C_ATTRIBUTE = "cyclesTraceInboundW3C";
    public static final String MDC_KEY = "traceId";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_SPAN_ID = "0000000000000000";

    /** Return the bundled trace context captured by this filter, or {@link TraceContext#EMPTY}. */
    public static TraceContext currentContext(HttpServletRequest request) {
        if (request == null) return TraceContext.EMPTY;
        Object traceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (traceId == null) return TraceContext.EMPTY;
        Object flags = request.getAttribute(TRACE_FLAGS_ATTRIBUTE);
        Object inbound = request.getAttribute(TRACE_INBOUND_W3C_ATTRIBUTE);
        return new TraceContext(
                traceId.toString(),
                flags != null ? flags.toString() : TraceContext.DEFAULT_FLAGS,
                inbound instanceof Boolean b ? b : Boolean.FALSE);
    }

    /** Convenience accessor for callers that only need the trace_id. */
    public static String currentTraceId(HttpServletRequest request) {
        return currentContext(request).traceId();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        TraceContext ctx = extractContext(request);
        request.setAttribute(TRACE_ID_ATTRIBUTE, ctx.traceId());
        request.setAttribute(TRACE_FLAGS_ATTRIBUTE, ctx.traceFlags());
        request.setAttribute(TRACE_INBOUND_W3C_ATTRIBUTE, ctx.traceparentInboundValid());
        response.setHeader(TRACE_ID_HEADER, ctx.traceId());
        MDC.put(MDC_KEY, ctx.traceId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private TraceContext extractContext(HttpServletRequest request) {
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        if (traceparent != null) {
            Matcher m = TRACEPARENT_PATTERN.matcher(traceparent.toLowerCase());
            if (m.matches()
                    && !ALL_ZERO_TRACE_ID.equals(m.group(1))
                    && !ALL_ZERO_SPAN_ID.equals(m.group(2))) {
                return new TraceContext(m.group(1), m.group(3), Boolean.TRUE);
            }
        }

        String headerTraceId = request.getHeader(TRACE_ID_HEADER);
        if (headerTraceId != null) {
            String candidate = headerTraceId.toLowerCase();
            if (TRACE_ID_PATTERN.matcher(candidate).matches()
                    && !ALL_ZERO_TRACE_ID.equals(candidate)) {
                return new TraceContext(candidate, TraceContext.DEFAULT_FLAGS, Boolean.FALSE);
            }
        }

        return TraceContext.generated(TraceIdGenerator.generate());
    }
}
