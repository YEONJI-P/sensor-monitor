package dev.bugi.sensor.sensordata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * C1 수신 계약. 한 물리 노드(deviceCode)의 한 관측 시점(batch) 값 묶음.
 * observedAt/sourceSeq 는 선택이며, measurements 는 채널 code → 값 map 이다.
 */
@Getter
@NoArgsConstructor
public class BatchIngestRequest {

    @NotBlank
    private String deviceCode;

    // 관측 시각(원본 기준). 없으면 서버 수신 시각으로 대체한다.
    private Instant observedAt;

    // 원본 순서(예: C-MAPSS cycle, CNC 행 번호). 선택.
    private Long sourceSeq;

    @NotEmpty
    private Map<String, Double> measurements;

    // producer 재시도 멱등성 키. deviceCode 범위에서만 유일하며 생략 가능하다.
    @Size(max = 128)
    @Pattern(regexp = ".*\\S.*", message = "eventId는 공백일 수 없습니다")
    private String eventId;

    /** 기존 4필드 호출자 호환 생성자. */
    public BatchIngestRequest(String deviceCode, Instant observedAt, Long sourceSeq,
                              Map<String, Double> measurements) {
        this(deviceCode, observedAt, sourceSeq, measurements, null);
    }

    public BatchIngestRequest(String deviceCode, Instant observedAt, Long sourceSeq,
                              Map<String, Double> measurements, String eventId) {
        this.deviceCode = deviceCode;
        this.observedAt = observedAt;
        this.sourceSeq = sourceSeq;
        this.measurements = measurements;
        this.eventId = eventId;
    }
}
