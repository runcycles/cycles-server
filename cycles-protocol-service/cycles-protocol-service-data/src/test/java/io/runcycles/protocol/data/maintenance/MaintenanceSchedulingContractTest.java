package io.runcycles.protocol.data.maintenance;

import io.runcycles.protocol.data.repository.AuditRepository;
import io.runcycles.protocol.data.repository.EventEmitterRepository;
import io.runcycles.protocol.data.service.ReservationCreatedAtIndexService;
import io.runcycles.protocol.data.service.ReservationExpiryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MaintenanceSchedulingContractTest {

    @Test
    void everyScheduledRedisMaintenanceJobDelegatesToTheSharedRunner() {
        RedisMaintenanceRunner runner = mock(RedisMaintenanceRunner.class);

        ReservationExpiryService expiry = new ReservationExpiryService();
        setField(expiry, "maintenanceRunner", runner);
        expiry.scheduledExpireReservations();

        AuditRepository audit = new AuditRepository();
        setField(audit, "maintenanceRunner", runner);
        setField(audit, "retentionDays", 400);
        audit.scheduledSweepStaleIndexEntries();

        EventEmitterRepository events = new EventEmitterRepository();
        setField(events, "maintenanceRunner", runner);
        events.scheduledSweepStaleIndexEntries();

        ReservationCreatedAtIndexService index = new ReservationCreatedAtIndexService();
        setField(index, "maintenanceRunner", runner);
        setField(index, "enabled", true);
        index.scheduledRepairIfRequested();
        index.scheduledSweepStaleMembers();

        verify(runner).run(eq(MaintenanceJob.RESERVATION_EXPIRY), any(Runnable.class));
        verify(runner).run(eq(MaintenanceJob.EVENT_RETENTION), any(Runnable.class));
        verify(runner).runIf(eq(MaintenanceJob.AUDIT_RETENTION), eq(true), any(Runnable.class));
        verify(runner).runIf(eq(MaintenanceJob.CREATED_AT_REPAIR), eq(true), any(Runnable.class));
        verify(runner).runIf(eq(MaintenanceJob.CREATED_AT_SWEEP), eq(true), any(Runnable.class));
    }

    @Test
    void scheduledMethodAndMetricTagSetsStayFixedAndBounded() {
        List<Class<?>> owners = findScheduledOwners();
        long scheduledMethods = owners.stream()
            .flatMap(owner -> Arrays.stream(owner.getDeclaredMethods()))
            .filter(method -> method.isAnnotationPresent(Scheduled.class))
            .count();

        assertThat(owners).extracting(Class::getName).containsExactly(
            "io.runcycles.protocol.data.repository.AuditRepository",
            "io.runcycles.protocol.data.repository.EventEmitterRepository",
            "io.runcycles.protocol.data.service.ReservationCreatedAtIndexService",
            "io.runcycles.protocol.data.service.ReservationExpiryService");
        assertThat(scheduledMethods).isEqualTo(5);
        assertThat(Arrays.stream(MaintenanceJob.values()).map(MaintenanceJob::tag))
            .containsExactly(
                "reservation_expiry", "audit_retention", "event_retention",
                "created_at_repair", "created_at_sweep");
        assertThat(Arrays.stream(MaintenanceOutcome.values()).map(MaintenanceOutcome::tag))
            .containsExactly(
                "success", "failed", "skipped_locked", "skipped_disabled",
                "lease_error", "lease_lost");
    }

    private static List<Class<?>> findScheduledOwners() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) ->
            metadataReader.getAnnotationMetadata()
                .hasAnnotatedMethods(Scheduled.class.getName()));
        return scanner.findCandidateComponents("io.runcycles.protocol").stream()
            .<Class<?>>map(definition -> loadClass(definition.getBeanClassName()))
            .sorted(Comparator.comparing(Class::getName))
            .toList();
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
