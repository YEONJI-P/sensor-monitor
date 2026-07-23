package dev.bugi.sensor.alert.service;

import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.Alert;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertNotificationStatus;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.sensordata.entity.MeasurementBatch;
import org.springframework.stereotype.Component;

/**
 * Wave 2 detector/scheduler가 같은 metadata 규칙으로 Alert를 만들기 위한 순수 factory.
 */
@Component
public class AlarmNotificationFactory {

    public Alert create(AlarmEpisode episode, Device representativeDevice,
                        SensorChannel channel, MeasurementBatch batch,
                        Double sensorValue, Double thresholdValue,
                        String message, AlertSeverity severity,
                        AlertNotificationReason reason) {
        if (episode == null || !episode.isOpen()) {
            throw new IllegalArgumentException("OPEN episode가 필요합니다");
        }
        return Alert.builder()
                .episode(episode)
                .alarmType(episode.getAlarmType())
                .scopeType(episode.getScopeType())
                .notificationReason(reason)
                .notificationStatus(AlertNotificationStatus.PENDING)
                .device(representativeDevice)
                .channel(channel)
                .batch(batch)
                .sensorValue(sensorValue)
                .thresholdValue(thresholdValue)
                .message(message)
                .severity(severity)
                .evidence(episode.getEvidence())
                .recommendation(episode.getRecommendation())
                .build();
    }
}
