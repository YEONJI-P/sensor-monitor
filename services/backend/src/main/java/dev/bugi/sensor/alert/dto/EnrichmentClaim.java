package dev.bugi.sensor.alert.dto;

import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;

import java.time.Instant;
import java.util.UUID;

/**
 * Claim transaction에서 값만 떼어 외부 HTTP 호출로 넘기는 projection.
 */
public record EnrichmentClaim(
        Long alertId,
        Long episodeId,
        UUID leaseToken,
        int attempt,
        AlarmType alarmType,
        Long deviceId,
        Long channelId,
        String deviceName,
        String sensorType,
        String unit,
        Double sensorValue,
        Double thresholdValue,
        ThresholdDirection thresholdDirection,
        String message,
        Integer expectedIntervalSeconds,
        Instant lastSeenAt,
        Integer elapsedSeconds
) {
}
