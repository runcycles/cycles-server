package io.runcycles.protocol.data.util;

/**
 * In-memory W3C Trace Context bundle threaded from the servlet filter
 * through async event emission to the webhook-delivery row.
 *
 * <p>Not a wire contract — {@code trace_id} is declared on
 * {@code ErrorResponse} / {@code Event} / {@code AuditLogEntry} /
 * {@code WebhookDelivery}; {@code trace_flags} and
 * {@code traceparent_inbound_valid} are declared on {@code WebhookDelivery}
 * only (admin spec v0.1.25.28). This record exists purely to keep the three
 * values grouped as they travel through internal method boundaries.
 *
 * <p>All fields nullable. {@link #EMPTY} signals no trace context available
 * (e.g., a call path with no originating request).
 */
public record TraceContext(String traceId, String traceFlags, Boolean traceparentInboundValid) {

    /** Default trace-flags value when the trace was derived or generated (not inbound W3C). */
    public static final String DEFAULT_FLAGS = "01";

    public static final TraceContext EMPTY = new TraceContext(null, null, null);

    /**
     * Trace context for server-generated trace_ids (background sweepers, internally-originated
     * events with no inbound HTTP request). Uses sampled-by-default trace-flags and explicitly
     * records that no valid inbound W3C {@code traceparent} was present.
     */
    public static TraceContext generated(String traceId) {
        return new TraceContext(traceId, DEFAULT_FLAGS, Boolean.FALSE);
    }
}
