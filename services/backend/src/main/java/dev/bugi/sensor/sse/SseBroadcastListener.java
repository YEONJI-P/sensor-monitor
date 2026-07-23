package dev.bugi.sensor.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 수신 트랜잭션이 커밋된 뒤에만 SSE로 전송한다.
 * 저장 트랜잭션 안에서 네트워크 I/O(broadcast)를 하지 않도록 분리 —
 * 롤백 시 유령 이벤트 방지 + 느린 구독자가 DB 커넥션을 잡지 않게 한다.
 */
@Component
@RequiredArgsConstructor
public class SseBroadcastListener {

    private final SseService sseService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBroadcast(SseBroadcastEvent event) {
        sseService.broadcast(
                event.event(), event.deviceId(), event.zoneId(), event.payload());
    }
}
