package dev.bugi.sensor.sensordata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "ingest_receipt",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ingest_receipt_device_event",
                columnNames = {"device_code", "event_id"})
)
public class IngestReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String deviceCode;

    @Column(nullable = false, length = 128)
    private String eventId;

    @Column(nullable = false, length = 128)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    private IngestOutcome outcome;

    private Integer httpStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Getter(AccessLevel.NONE)
    private Map<String, Object> responseJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToOne(mappedBy = "receipt")
    private MeasurementBatch batch;

    public IngestReceipt(String deviceCode, String eventId, String requestHash, Instant createdAt) {
        this.deviceCode = requireText(deviceCode, "deviceCode");
        this.eventId = requireText(eventId, "eventId");
        this.requestHash = requireText(requestHash, "requestHash");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean hasSameRequest(String requestHash) {
        return this.requestHash.equals(requestHash);
    }

    public boolean isCompleted() {
        return outcome != null;
    }

    public Map<String, Object> getResponseJson() {
        return responseJson == null ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(responseJson));
    }

    public void complete(IngestOutcome outcome, int httpStatus, Map<String, Object> responseJson) {
        if (isCompleted()) {
            throw new IllegalStateException("이미 완료된 ingest receipt입니다");
        }
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.httpStatus = httpStatus;
        this.responseJson = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(responseJson, "responseJson")));
    }

    void attachBatch(MeasurementBatch batch) {
        if (this.batch != null && this.batch != batch) {
            throw new IllegalStateException("ingest receipt에는 batch를 한 번만 연결할 수 있습니다");
        }
        this.batch = batch;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "는 비어 있을 수 없습니다");
        }
        return value;
    }
}
