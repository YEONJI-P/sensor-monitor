package dev.bugi.sensor.device.freshness;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 읽기 단계의 freshness 판정을 짧은 lifecycle transaction으로 넘기는 값 객체.
 */
public record FreshnessDeviceState(
        Long deviceId,
        Integer expectedIntervalSeconds,
        Instant lastSeenAt,
        FreshnessPolicy.State state,
        Map<String, Object> snapshot
) {
    public FreshnessDeviceState {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(state, "state");
        snapshot = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(snapshot, "snapshot")));
    }
}
