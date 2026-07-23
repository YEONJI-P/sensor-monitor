package dev.bugi.sensor.alert.dto;

import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import dev.bugi.sensor.alert.entity.AlertSeverity;

import java.time.Instant;

public record AlarmAcknowledgementResponse(
        Long id,
        Long userId,
        String userName,
        AlertSeverity ackSeverity,
        Instant createdAt
) {
    public static AlarmAcknowledgementResponse from(AlarmAcknowledgement acknowledgement) {
        if (acknowledgement == null) {
            return null;
        }
        return new AlarmAcknowledgementResponse(
                acknowledgement.getId(),
                acknowledgement.getUser().getId(),
                acknowledgement.getUser().getName(),
                acknowledgement.getAckSeverity(),
                acknowledgement.getCreatedAt());
    }
}
