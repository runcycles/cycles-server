package io.runcycles.protocol.data.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.runcycles.protocol.data.exception.CyclesProtocolException;
import io.runcycles.protocol.data.repository.support.ReservationListQuery;
import io.runcycles.protocol.model.Action;
import io.runcycles.protocol.model.Amount;
import io.runcycles.protocol.model.CyclesEvidenceRef;
import io.runcycles.protocol.model.Enums;
import io.runcycles.protocol.model.ReservationDetail;
import io.runcycles.protocol.model.ReservationEvidence;
import io.runcycles.protocol.model.ReservationInclude;
import io.runcycles.protocol.model.ReservationSummary;
import io.runcycles.protocol.model.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical Redis-hash projection, filtering, and wire-model hydration. */
@Component
public class ReservationHashMapper {

    private static final List<String> SUMMARY_FIELDS = List.of(
        "reservation_id", "tenant", "state", "subject_json", "action_json",
        "estimate_amount", "estimate_unit", "scope_path", "affected_scopes",
        "created_at", "expires_at", "idempotency_key", "charged_amount",
        "committed_at", "released_at");
    private static final List<String> EVIDENCE_FIELDS = List.of(
        "reserve_evidence_id", "reserve_evidence_url",
        "commit_evidence_id", "commit_evidence_url",
        "release_evidence_id", "release_evidence_url");
    private static final List<String> DETAIL_FIELDS = List.copyOf(
        projection(Collections.emptySet(), true));
    private static final String[] DETAIL_FIELD_ARRAY = DETAIL_FIELDS.toArray(String[]::new);

    private final ObjectMapper objectMapper;

    @Autowired
    public ReservationHashMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> detailFields() {
        return DETAIL_FIELDS;
    }

    public String[] detailFieldArray() {
        return DETAIL_FIELD_ARRAY.clone();
    }

    public static List<String> projection(Set<ReservationInclude> include, boolean detail) {
        Set<ReservationInclude> requested = include == null ? Collections.emptySet() : include;
        List<String> fields = new ArrayList<>(SUMMARY_FIELDS);
        if (detail || requested.contains(ReservationInclude.METADATA)) {
            fields.add("metadata_json");
        }
        if (detail || requested.contains(ReservationInclude.COMMITTED_METADATA)) {
            fields.add("committed_metadata_json");
        }
        if (detail || requested.contains(ReservationInclude.EVIDENCE)) {
            fields.addAll(EVIDENCE_FIELDS);
        }
        return fields;
    }

    public ReservationSummary matchingSummary(Map<String, String> fields,
                                              ReservationListQuery query) throws Exception {
        if (!query.tenant().equals(fields.get("tenant"))) return null;
        if (query.status() != null && !query.status().equals(fields.get("state"))) return null;
        if (query.idempotencyKey() != null
                && !query.idempotencyKey().equals(fields.get("idempotency_key"))) return null;
        if (!query.scopes().matches(fields.get("scope_path"))) return null;
        if (!query.createdAt().contains(fields.get("created_at"))) return null;
        if (!query.expiresAt().contains(fields.get("expires_at"))) return null;
        if (!query.finalizedAt().contains(resolveFinalizedAt(fields))) return null;
        return toSummary(buildDetail(fields), query.include());
    }

    public ReservationSummary toSummary(ReservationDetail detail, Set<ReservationInclude> include) {
        Set<ReservationInclude> requested = include == null ? Collections.emptySet() : include;
        ReservationSummary.ReservationSummaryBuilder builder = ReservationSummary.builder()
            .reservationId(detail.getReservationId())
            .status(detail.getStatus())
            .idempotencyKey(detail.getIdempotencyKey())
            .subject(detail.getSubject())
            .action(detail.getAction())
            .reserved(detail.getReserved())
            .createdAtMs(detail.getCreatedAtMs())
            .expiresAtMs(detail.getExpiresAtMs())
            .finalizedAtMs(detail.getFinalizedAtMs())
            .scopePath(detail.getScopePath())
            .affectedScopes(detail.getAffectedScopes())
            .committed(detail.getCommitted());
        if (requested.contains(ReservationInclude.METADATA)) {
            builder.metadata(detail.getMetadata());
        }
        if (requested.contains(ReservationInclude.COMMITTED_METADATA)) {
            builder.committedMetadata(detail.getCommittedMetadata());
        }
        if (requested.contains(ReservationInclude.EVIDENCE)) {
            builder.evidence(detail.getEvidence());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    public ReservationDetail buildDetail(Map<String, String> fields) throws Exception {
        String estimateUnit = fields.get("estimate_unit");
        String estimateAmount = fields.get("estimate_amount");
        String state = fields.get("state");
        String subjectJson = fields.get("subject_json");
        String actionJson = fields.get("action_json");
        String createdAt = fields.get("created_at");
        String expiresAt = fields.get("expires_at");
        if (estimateUnit == null || estimateAmount == null || state == null
                || subjectJson == null || actionJson == null
                || createdAt == null || expiresAt == null) {
            throw new CyclesProtocolException(Enums.ErrorCode.INTERNAL_ERROR,
                "Reservation data is corrupted: reservationId=" + fields.get("reservation_id"), 500);
        }

        Enums.UnitEnum unit = Enums.UnitEnum.valueOf(estimateUnit);
        String affectedScopesJson = fields.get("affected_scopes");
        List<String> affectedScopes = affectedScopesJson == null
            ? Collections.emptyList()
            : objectMapper.readValue(affectedScopesJson, List.class);

        Amount committed = null;
        String chargedAmount = fields.get("charged_amount");
        if (chargedAmount != null) {
            committed = new Amount(unit, Long.parseLong(chargedAmount));
        }
        Long finalizedAt = null;
        String finalizedAtRaw = resolveFinalizedAt(fields);
        if (finalizedAtRaw != null) {
            finalizedAt = Long.parseLong(finalizedAtRaw);
        }

        Map<String, Object> metadata = readOptionalMap(fields.get("metadata_json"));
        Map<String, Object> committedMetadata = readOptionalMap(fields.get("committed_metadata_json"));

        ReservationDetail detail = new ReservationDetail();
        detail.setCommitted(committed);
        detail.setFinalizedAtMs(finalizedAt);
        detail.setMetadata(metadata);
        detail.setCommittedMetadata(committedMetadata);
        detail.setReservationId(fields.get("reservation_id"));
        detail.setStatus(Enums.ReservationStatus.valueOf(state));
        detail.setIdempotencyKey(fields.get("idempotency_key"));
        detail.setSubject(objectMapper.readValue(subjectJson, Subject.class));
        detail.setAction(objectMapper.readValue(actionJson, Action.class));
        detail.setReserved(new Amount(unit, Long.parseLong(estimateAmount)));
        detail.setCreatedAtMs(Long.parseLong(createdAt));
        detail.setExpiresAtMs(Long.parseLong(expiresAt));
        detail.setScopePath(fields.get("scope_path"));
        detail.setAffectedScopes(affectedScopes);
        detail.setEvidence(buildEvidence(fields));
        return detail;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readOptionalMap(String json) throws Exception {
        return json == null || json.isEmpty() ? null : objectMapper.readValue(json, Map.class);
    }

    public static String resolveFinalizedAt(Map<String, String> fields) {
        String releasedAt = fields.get("released_at");
        return releasedAt != null ? releasedAt : fields.get("committed_at");
    }

    public static ReservationEvidence buildEvidence(Map<String, String> fields) {
        ReservationEvidence.ReservationEvidenceBuilder builder = ReservationEvidence.builder();
        boolean any = false;
        for (String artifact : List.of("reserve", "commit", "release")) {
            String id = fields.get(artifact + "_evidence_id");
            String url = fields.get(artifact + "_evidence_url");
            if (id == null || id.isEmpty() || url == null || url.isEmpty()) continue;
            CyclesEvidenceRef ref = CyclesEvidenceRef.builder()
                .evidenceId(id).cyclesEvidenceUrl(url).build();
            switch (artifact) {
                case "reserve" -> builder.reserve(ref);
                case "commit" -> builder.commit(ref);
                case "release" -> builder.release(ref);
                default -> throw new IllegalStateException("Unknown evidence artifact: " + artifact);
            }
            any = true;
        }
        return any ? builder.build() : null;
    }
}
