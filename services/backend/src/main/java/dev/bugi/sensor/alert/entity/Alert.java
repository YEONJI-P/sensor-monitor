package dev.bugi.sensor.alert.entity;

import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.sensordata.entity.MeasurementBatch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private SensorChannel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private MeasurementBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "episode_id")
    private AlarmEpisode episode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlarmType alarmType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlarmScopeType scopeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertNotificationReason notificationReason;

    // reading FK 대신 값 직접 저장 (스냅샷 방식 - reading 삭제돼도 이력 보존)
    private Double sensorValue;
    private Double thresholdValue;
    private String message;

    @Enumerated(EnumType.STRING)
    private AlertSeverity severity;

    private String evidence;
    private String recommendation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertNotificationStatus notificationStatus = AlertNotificationStatus.NOT_REQUESTED;

    @Column(nullable = false)
    private int notificationAttempts;

    private Instant lastNotificationAttemptAt;
    private Instant notifiedAt;

    @Column(length = 1000)
    private String notificationError;

    @Column(nullable = false)
    @ColumnDefault("0")
    private int enrichmentAttempts;

    private Instant enrichmentClaimedAt;
    private UUID enrichmentLeaseToken;
    private Instant enrichmentNextAttemptAt;
    private Instant enrichmentCompletedAt;

    @Column(length = 1000)
    private String enrichmentError;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Builder
    public Alert(Device device, SensorChannel channel, MeasurementBatch batch, AlarmEpisode episode,
                 AlarmType alarmType, AlarmScopeType scopeType,
                 AlertNotificationReason notificationReason,
                 Double sensorValue, Double thresholdValue, String message,
                 AlertSeverity severity, String evidence, String recommendation,
                 AlertNotificationStatus notificationStatus) {
        this.device = device;
        this.channel = channel;
        this.batch = batch;
        this.episode = episode;
        this.alarmType = alarmType;
        this.scopeType = scopeType;
        this.notificationReason = notificationReason;
        this.sensorValue = sensorValue;
        this.thresholdValue = thresholdValue;
        this.message = message;
        this.severity = severity;
        this.evidence = evidence;
        this.recommendation = recommendation;
        if (notificationStatus != null) {
            this.notificationStatus = notificationStatus;
        }
        normalizeMetadata();
    }

    public void enrich(String evidence, String recommendation) {
        this.evidence = evidence;
        this.recommendation = recommendation;
    }

    public void queueNotification() {
        this.notificationStatus = AlertNotificationStatus.PENDING;
        this.notificationError = null;
    }

    public void markNotificationAttempt(Instant at) {
        this.notificationAttempts++;
        this.lastNotificationAttemptAt = at;
    }

    public void markNotificationSent(Instant at) {
        this.notificationStatus = AlertNotificationStatus.SENT;
        this.notifiedAt = at;
        this.notificationError = null;
    }

    public void markNotificationFailed(String error) {
        this.notificationStatus = AlertNotificationStatus.FAILED;
        this.notificationError = error;
    }

    public void skipNotification(String reason) {
        this.notificationStatus = AlertNotificationStatus.SKIPPED;
        this.notificationError = reason;
    }

    @PrePersist
    void normalizeMetadata() {
        boolean legacyZoneSilence = episode == null && channel == null
                && message != null && message.startsWith("구역 전체 수신 끊김");
        if (alarmType == null) {
            alarmType = episode != null ? episode.getAlarmType()
                    : channel != null ? AlarmType.THRESHOLD
                    : legacyZoneSilence ? AlarmType.ZONE_SILENCE : AlarmType.DEVICE_SILENCE;
        }
        if (scopeType == null) {
            scopeType = episode != null ? episode.getScopeType()
                    : channel != null ? AlarmScopeType.CHANNEL
                    : legacyZoneSilence ? AlarmScopeType.ZONE : AlarmScopeType.DEVICE;
        }
        if (notificationReason == null) {
            notificationReason = episode == null
                    ? AlertNotificationReason.LEGACY : AlertNotificationReason.INITIAL;
        }
        if (notificationStatus == null) {
            notificationStatus = AlertNotificationStatus.NOT_REQUESTED;
        }
    }
}
