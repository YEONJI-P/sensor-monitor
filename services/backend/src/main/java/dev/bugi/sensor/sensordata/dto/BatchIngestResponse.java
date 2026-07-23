package dev.bugi.sensor.sensordata.dto;

import java.time.Instant;
import java.util.List;

/**
 * C2 응답 본문. 부분 실패(rejected)를 포함해도 batch 가 생성되면 200 이다.
 * 404(장치 없음)·422(전 채널 미지)에서는 batchId 가 null 이다.
 */
public record BatchIngestResponse(
        Long batchId,
        Long deviceId,
        String deviceCode,
        Instant observedAt,
        Instant receivedAt,
        int savedCount,
        List<RejectedReading> rejected,
        String eventId,
        int stateAppliedCount
) {
    /** 기존 응답 생성 코드 호환 생성자. 새 통계는 멱등 키 없음/상태 미적용으로 둔다. */
    public BatchIngestResponse(Long batchId, Long deviceId, String deviceCode,
                               Instant observedAt, Instant receivedAt, int savedCount,
                               List<RejectedReading> rejected) {
        this(batchId, deviceId, deviceCode, observedAt, receivedAt,
                savedCount, rejected, null, 0);
    }
}
