package dev.bugi.sensor.alert.entity;

import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.factory.entity.Zone;
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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "alarm_episode")
@EntityListeners(AuditingEntityListener.class)
public class AlarmEpisode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "alarm_type", nullable = false)
    private AlarmType alarmType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlarmScopeType scopeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlarmEpisodeStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity currentSeverity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity maxSeverity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private SensorChannel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    @Column(nullable = false)
    private Instant openedAt;

    private Instant resolvedAt;

    @Enumerated(EnumType.STRING)
    private AlarmResolutionReason resolutionReason;

    private Instant lastNotifiedAt;
    private String evidence;
    private String recommendation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    @Getter(AccessLevel.NONE)
    private Map<String, Object> snapshot;

    @Column(nullable = false)
    private boolean legacyBackfill;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    private AlarmEpisode(AlarmType alarmType, AlarmScopeType scopeType,
                         Device device, SensorChannel channel, Zone zone,
                         AlertSeverity severity, Instant openedAt,
                         Map<String, Object> snapshot, boolean legacyBackfill) {
        this.alarmType = Objects.requireNonNull(alarmType, "alarmType");
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType");
        this.device = device;
        this.channel = channel;
        this.zone = zone;
        this.currentSeverity = Objects.requireNonNull(severity, "severity");
        this.maxSeverity = severity;
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.snapshot = immutableSnapshot(snapshot);
        this.legacyBackfill = legacyBackfill;
        this.status = AlarmEpisodeStatus.OPEN;
    }

    public static AlarmEpisode openThreshold(Device device, SensorChannel channel,
                                             AlertSeverity severity, Instant openedAt,
                                             Map<String, Object> snapshot) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(channel, "channel");
        if (channel.getDevice() == null || !Objects.equals(device.getId(), channel.getDevice().getId())) {
            throw new IllegalArgumentException("threshold episode의 device와 channel 소유자가 일치해야 합니다");
        }
        return new AlarmEpisode(AlarmType.THRESHOLD, AlarmScopeType.CHANNEL,
                device, channel, null, severity, openedAt, snapshot, false);
    }

    public static AlarmEpisode openDeviceSilence(Device device, AlertSeverity severity,
                                                 Instant openedAt, Map<String, Object> snapshot) {
        return new AlarmEpisode(AlarmType.DEVICE_SILENCE, AlarmScopeType.DEVICE,
                Objects.requireNonNull(device, "device"), null, null,
                severity, openedAt, snapshot, false);
    }

    public static AlarmEpisode openZoneSilence(Zone zone, AlertSeverity severity,
                                               Instant openedAt, Map<String, Object> snapshot) {
        return new AlarmEpisode(AlarmType.ZONE_SILENCE, AlarmScopeType.ZONE,
                null, null, Objects.requireNonNull(zone, "zone"),
                severity, openedAt, snapshot, false);
    }

    public Map<String, Object> getSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }

    public void transitionSeverity(AlertSeverity severity) {
        ensureOpen();
        this.currentSeverity = Objects.requireNonNull(severity, "severity");
        if (rank(severity) > rank(maxSeverity)) {
            this.maxSeverity = severity;
        }
    }

    public void updateDiagnosis(String evidence, String recommendation) {
        ensureOpen();
        this.evidence = evidence;
        this.recommendation = recommendation;
    }

    public void markNotified(Instant at) {
        ensureOpen();
        this.lastNotifiedAt = Objects.requireNonNull(at, "at");
    }

    public void resolve(Instant at, AlarmResolutionReason reason) {
        ensureOpen();
        Objects.requireNonNull(at, "at");
        if (at.isBefore(openedAt)) {
            throw new IllegalArgumentException("resolvedAt은 openedAt보다 이를 수 없습니다");
        }
        this.status = AlarmEpisodeStatus.RESOLVED;
        this.resolvedAt = at;
        this.resolutionReason = Objects.requireNonNull(reason, "reason");
    }

    public boolean isOpen() {
        return status == AlarmEpisodeStatus.OPEN;
    }

    private void ensureOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("이미 종료된 alarm episode입니다");
        }
    }

    private static Map<String, Object> immutableSnapshot(Map<String, Object> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return Collections.unmodifiableMap(new LinkedHashMap<>(snapshot));
    }

    private static int rank(AlertSeverity severity) {
        return switch (severity) {
            case INFO -> 0;
            case WARNING -> 1;
            case CRITICAL -> 2;
        };
    }
}
