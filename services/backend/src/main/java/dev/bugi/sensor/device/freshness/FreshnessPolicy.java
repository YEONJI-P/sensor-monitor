package dev.bugi.sensor.device.freshness;

import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService.OperatingDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Scheduler와 dashboard가 공유하는 freshness 경계.
 */
public final class FreshnessPolicy {

    public static final long STALE_AFTER_INTERVALS = 2L;

    private FreshnessPolicy() {
    }

    public static State evaluate(Integer expectedIntervalSeconds, Instant lastSeenAt, Instant now,
                                 OperatingDecision operatingDecision) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(operatingDecision, "operatingDecision");
        if (expectedIntervalSeconds == null || expectedIntervalSeconds <= 0) {
            return State.NOT_MONITORED;
        }

        long staleAfterSeconds = staleAfterSeconds(expectedIntervalSeconds);
        if (lastSeenAt != null && elapsedSeconds(lastSeenAt, now) <= staleAfterSeconds) {
            return State.ONLINE;
        }
        if (!operatingDecision.scheduledActive()) {
            return State.PLANNED_OFFLINE;
        }
        if (operatingDecision.monitoringSuppressed(lastSeenAt)) {
            return State.RESUME_GRACE;
        }
        if (lastSeenAt == null) {
            return State.NEVER_SEEN;
        }
        return State.STALE;
    }

    public static long staleAfterSeconds(int expectedIntervalSeconds) {
        if (expectedIntervalSeconds <= 0) {
            throw new IllegalArgumentException("expectedIntervalSeconds는 양수여야 합니다");
        }
        return Math.multiplyExact((long) expectedIntervalSeconds, STALE_AFTER_INTERVALS);
    }

    public static long elapsedSeconds(Instant lastSeenAt, Instant now) {
        return Math.max(0L, Duration.between(lastSeenAt, now).getSeconds());
    }

    public enum State {
        NOT_MONITORED,
        PLANNED_OFFLINE,
        RESUME_GRACE,
        NEVER_SEEN,
        ONLINE,
        STALE
    }
}
