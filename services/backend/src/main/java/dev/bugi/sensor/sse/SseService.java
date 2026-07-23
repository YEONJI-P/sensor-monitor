package dev.bugi.sensor.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 대시보드 실시간 전송(SSE) 관리.
 * 구독 시점의 접근 가능 device/zone 집합을 emitter에 바인딩하고, 이벤트 scope가
 * 그 집합에 있을 때만 전송한다 — REST 조회와 동일한 접근 범위를 실시간 채널에도 적용.
 * 메시지 버스 없이 인프로세스 메서드 호출로만 동작한다.
 */
@Slf4j
@Service
public class SseService {

    private static final long TIMEOUT = 30 * 60 * 1000L; // 30분

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /** 구독자와 그가 볼 수 있는 device 범위 */
    private record Subscriber(
            SseEmitter emitter, Set<Long> deviceIds, Set<Long> zoneIds) {
    }

    public SseEmitter subscribe(Set<Long> deviceIds) {
        return subscribe(deviceIds, Set.of());
    }

    public SseEmitter subscribe(Set<Long> deviceIds, Set<Long> zoneIds) {
        SseEmitter emitter = new SseEmitter(TIMEOUT);
        Subscriber subscriber = new Subscriber(
                emitter, Set.copyOf(deviceIds), Set.copyOf(zoneIds));
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(e -> subscribers.remove(subscriber));
        send(subscriber, "connected", "ok");
        return emitter;
    }

    /** deviceId 이벤트를 그 장치에 접근 가능한 구독자에게만 전송한다. */
    public void broadcast(String event, Long deviceId, Object data) {
        broadcast(event, deviceId, null, data);
    }

    /** device 또는 zone scope 이벤트를 접근 가능한 구독자에게만 전송한다. */
    public void broadcast(
            String event, Long deviceId, Long zoneId, Object data) {
        for (Subscriber subscriber : subscribers) {
            if (deviceId != null && !subscriber.deviceIds().contains(deviceId)) {
                continue;
            }
            if (zoneId != null && !subscriber.zoneIds().contains(zoneId)) {
                continue;
            }
            send(subscriber, event, data);
        }
    }

    private void send(Subscriber subscriber, String event, Object data) {
        // SseEmitter.send는 단일 emitter 기준 스레드 세이프하지 않으므로 emitter별로 직렬화한다.
        synchronized (subscriber.emitter()) {
            try {
                subscriber.emitter().send(SseEmitter.event().name(event).data(data));
            } catch (IOException e) {
                subscribers.remove(subscriber);
                subscriber.emitter().completeWithError(e);
            }
        }
    }
}
