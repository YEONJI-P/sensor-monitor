package dev.bugi.sensor.alert.dto;

import dev.bugi.sensor.alert.entity.Alert;
import dev.bugi.sensor.alert.entity.AlarmScopeType;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AlertResponse {
    private Long id;
    // 임계 alert 는 device·channel·batch 셋 다, freshness alert 는 device 만 세팅된다(channelId·batchId=null).
    private Long deviceId;
    private Long channelId;
    private Long batchId;
    private Double sensorValue;
    private Double thresholdValue;
    private String message;
    private AlertSeverity severity;
    private String evidence;
    private String recommendation;
    private Instant createdAt;
    // 기존 alert projection을 깨지 않는 additive lifecycle metadata.
    private AlarmType type;
    private AlarmScopeType scope;
    private Long episodeId;
    private AlertNotificationReason notificationReason;

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getDevice() != null ? alert.getDevice().getId() : null,
                alert.getChannel() != null ? alert.getChannel().getId() : null,
                alert.getBatch() != null ? alert.getBatch().getId() : null,
                alert.getSensorValue(),
                alert.getThresholdValue(),
                alert.getMessage(),
                alert.getSeverity(),
                alert.getEvidence(),
                alert.getRecommendation(),
                alert.getCreatedAt(),
                alert.getAlarmType(),
                alert.getScopeType(),
                alert.getEpisode() != null ? alert.getEpisode().getId() : null,
                alert.getNotificationReason()
        );
    }
}
