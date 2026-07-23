package dev.bugi.sensor.sensordata.entity;

import dev.bugi.sensor.device.entity.Device;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 한 payload 또는 원본 데이터 한 행의 관측 묶음. 같은 batch 의 채널 값은 같은 observed_at 을 공유한다.
 *
 * receivedAt 은 감사 필드(@CreatedDate)가 아니다 — 서비스가 주입 Clock 으로 명시 세팅한다.
 * 그래서 이 엔티티에는 AuditingEntityListener 를 붙이지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "measurement_batch",
        indexes = @Index(name = "idx_measurement_batch_device_observed", columnList = "device_id, observed_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_measurement_batch_receipt", columnNames = "receipt_id")
)
public class MeasurementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    // 관측 시각(원본 데이터 기준). 요청에 없으면 서비스가 receivedAt 으로 채운다.
    private Instant observedAt;

    // 수신 시각(서버 기준). 서비스가 주입 Clock 으로 세팅한다.
    private Instant receivedAt;

    // 원본 순서 보존용(예: C-MAPSS cycle, CNC 행 번호). 재전송·중복 감지는 아직 구현하지 않았다.
    private Long sourceSeq;

    // producer eventId의 범위/요청 hash는 receipt가 소유하고 batch는 surrogate FK만 참조한다.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receipt_id")
    private IngestReceipt receipt;

    @Builder
    public MeasurementBatch(Device device, Instant observedAt, Instant receivedAt, Long sourceSeq,
                            IngestReceipt receipt) {
        this.device = device;
        this.observedAt = observedAt;
        this.receivedAt = receivedAt;
        this.sourceSeq = sourceSeq;
        if (receipt != null) {
            attachReceipt(receipt);
        }
    }

    public void attachReceipt(IngestReceipt receipt) {
        if (this.receipt != null && this.receipt != receipt) {
            throw new IllegalStateException("measurement batch에는 receipt를 한 번만 연결할 수 있습니다");
        }
        this.receipt = receipt;
        receipt.attachBatch(this);
    }
}
