package dev.bugi.sensor.alert.dto;

import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.entity.AlarmResolutionReason;
import dev.bugi.sensor.alert.entity.AlarmScopeType;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.entity.AlertSeverity;

import java.time.Instant;
import java.util.Map;

public record AlarmEpisodeResponse(
        Long id,
        AlarmType type,
        AlarmScopeType scopeType,
        AlarmEpisodeStatus status,
        AlertSeverity currentSeverity,
        AlertSeverity maxSeverity,
        Long deviceId,
        Long channelId,
        Long zoneId,
        Instant openedAt,
        Instant resolvedAt,
        AlarmResolutionReason resolutionReason,
        Instant lastNotifiedAt,
        String evidence,
        String recommendation,
        Map<String, Object> snapshot,
        AlarmAcknowledgementResponse latestAcknowledgement,
        Instant createdAt,
        Instant updatedAt
) {
    public static AlarmEpisodeResponse from(
            AlarmEpisode episode, AlarmAcknowledgement latestAcknowledgement) {
        return new AlarmEpisodeResponse(
                episode.getId(),
                episode.getAlarmType(),
                episode.getScopeType(),
                episode.getStatus(),
                episode.getCurrentSeverity(),
                episode.getMaxSeverity(),
                episode.getDevice() == null ? null : episode.getDevice().getId(),
                episode.getChannel() == null ? null : episode.getChannel().getId(),
                episode.getZone() == null ? null : episode.getZone().getId(),
                episode.getOpenedAt(),
                episode.getResolvedAt(),
                episode.getResolutionReason(),
                episode.getLastNotifiedAt(),
                episode.getEvidence(),
                episode.getRecommendation(),
                episode.getSnapshot(),
                AlarmAcknowledgementResponse.from(latestAcknowledgement),
                episode.getCreatedAt(),
                episode.getUpdatedAt());
    }
}
