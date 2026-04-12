package io.runcycles.protocol.api.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 contract check — DTO CONSTRAINT DRIFT.
 *
 * <p>For every request body DTO used on a {@code @RequestBody} controller
 * parameter, asserts that every property the spec marks {@code required} has
 * a corresponding Java field annotated with one of {@code @NotNull},
 * {@code @NotBlank}, or {@code @NotEmpty}. Catches the class of drift where
 * the spec tightens a field to required but the DTO still accepts null,
 * allowing a 200 response on a structurally-invalid request.
 *
 * <p>The DTO's field-to-property mapping follows {@code @JsonProperty}; if
 * absent, the field name is compared directly.
 *
 * <p>Deliberately conservative: only checks {@code required} → {@code @NotNull}.
 * Ranges ({@code maxLength}/{@code pattern}) vary enough between spec style
 * and Bean Validation that a strict check produces too many false positives;
 * runtime @Valid in prod plus the response-schema validator cover most cases.
 */
class DtoConstraintContractTest {

    /** Request DTOs known to be used on {@code @RequestBody} params. */
    private static final List<Class<?>> REQUEST_DTOS = List.of(
            io.runcycles.protocol.model.DecisionRequest.class,
            io.runcycles.protocol.model.EventCreateRequest.class,
            io.runcycles.protocol.model.ReservationCreateRequest.class,
            io.runcycles.protocol.model.CommitRequest.class,
            io.runcycles.protocol.model.ReleaseRequest.class,
            io.runcycles.protocol.model.ReservationExtendRequest.class
    );

    @Test
    @EnabledIf(value = "io.runcycles.protocol.api.contract.ContractValidationConfig#validationEnabled",
               disabledReason = "contract.validation.enabled=false — skipping DTO constraint check")
    void specRequiredFields_haveCorrespondingNotNullOnDto() {
        OpenAPI spec = new OpenAPIV3Parser().readContents(ContractSpecLoader.loadSpec())
                .getOpenAPI();
        Map<String, Schema> schemas = spec.getComponents().getSchemas();

        List<String> violations = new ArrayList<>();
        for (Class<?> dtoClass : REQUEST_DTOS) {
            Schema<?> schema = schemas.get(dtoClass.getSimpleName());
            if (schema == null) {
                violations.add(dtoClass.getSimpleName() + " — no matching spec schema");
                continue;
            }
            List<String> requiredProps = schema.getRequired();
            if (requiredProps == null || requiredProps.isEmpty()) continue;

            for (String prop : requiredProps) {
                Field field = findFieldByJsonName(dtoClass, prop);
                if (field == null) {
                    violations.add(dtoClass.getSimpleName() + "." + prop
                            + " — spec requires this but DTO has no matching field");
                    continue;
                }
                if (!hasNotNullAnnotation(field)) {
                    violations.add(dtoClass.getSimpleName() + "." + prop
                            + " (field " + field.getName()
                            + ") — spec requires this but field lacks @NotNull/@NotBlank/@NotEmpty");
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "DTO constraint drift vs spec:\n  - " + String.join("\n  - ", violations));
    }

    private static Field findFieldByJsonName(Class<?> cls, String jsonName) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                JsonProperty jp = f.getAnnotation(JsonProperty.class);
                String name = (jp != null && !jp.value().isEmpty()) ? jp.value() : f.getName();
                if (name.equals(jsonName)) return f;
            }
        }
        return null;
    }

    private static boolean hasNotNullAnnotation(Field field) {
        for (Annotation a : field.getAnnotations()) {
            Class<? extends Annotation> t = a.annotationType();
            if (t == NotNull.class || t == NotBlank.class || t == NotEmpty.class) return true;
        }
        return false;
    }
}
