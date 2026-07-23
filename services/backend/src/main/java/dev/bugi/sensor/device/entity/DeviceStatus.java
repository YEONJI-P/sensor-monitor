package dev.bugi.sensor.device.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 장치의 런타임 텔레메트리(수신 하트비트). 설정 엔티티인 Device 와 분리해 둔다.
 *
 * 분리 이유: lastSeenAt 은 센서 수신마다 갱신되는 고빈도 값이라, Device 에 두면
 * (1) 매 수신이 Device 행을 dirty 시켜 @LastModifiedDate(updatedAt) 를 오염시키고
 *     — updatedAt 은 이름·위치 같은 "설정 변경"만 감사해야 한다 —
 * (2) 설정 행이 매 수신마다 다시 쓰여 불필요하게 뜨거워진다.
 *
 * device_id 를 Device 와 공유 PK(@MapsId)로 쓴다(장치당 1행).
 * 알람 상태는 채널 경계로 옮겨 ChannelStatus 가 가진다 — DeviceStatus 는 노드 freshness 만 담당한다.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "device_status")
public class DeviceStatus {

    @Id
    private Long deviceId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "device_id")
    private Device device;

    private Instant lastSeenAt;

    public DeviceStatus(Device device) {
        this(device, null);
    }

    public DeviceStatus(Device device, Instant lastSeenAt) {
        this.device = device;
        this.lastSeenAt = lastSeenAt;
    }

    public void markSeen(Instant at) {
        this.lastSeenAt = at;
    }
}
