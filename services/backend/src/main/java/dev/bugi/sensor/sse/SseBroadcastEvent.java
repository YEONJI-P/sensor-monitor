package dev.bugi.sensor.sse;

/**
 * 실시간 전송 대상 이벤트. 트랜잭션 커밋 후 SSE로 흘려보내기 위해 사용한다.
 * deviceId 또는 zoneId는 접근 범위 필터에 쓰인다. 둘 다 null이면 전체 대상이다.
 */
public record SseBroadcastEvent(String event, Long deviceId, Long zoneId, Object payload) {

    public SseBroadcastEvent(String event, Long deviceId, Object payload) {
        this(event, deviceId, null, payload);
    }

    public static SseBroadcastEvent forZone(String event, Long zoneId, Object payload) {
        return new SseBroadcastEvent(event, null, zoneId, payload);
    }
}
