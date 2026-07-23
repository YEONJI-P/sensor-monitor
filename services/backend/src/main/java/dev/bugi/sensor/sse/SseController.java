package dev.bugi.sensor.sse;

import dev.bugi.sensor.auth.util.JwtUtil;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashSet;
import java.util.Set;

/**
 * 대시보드 실시간 스트림 구독 엔드포인트.
 *
 * EventSource는 Authorization 헤더를 실을 수 없어, JWT를 {@code ?token=<JWT>}
 * 쿼리파라미터로 받아 컨트롤러에서 직접 검증한다(SecurityConfig에서 permitAll).
 * access 토큰만 허용하며, 구독자의 접근 가능 device 범위를 계산해 SSE에 바인딩한다.
 */
@RestController
@RequiredArgsConstructor
public class SseController {

    private final SseService sseService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final AccessControlService accessControlService;

    @GetMapping(value = "/dashboard/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "token", required = false) String token) {
        if (token == null || !jwtUtil.validateAccessToken(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.");
        }
        User user = userRepository.findByEmployeeId(jwtUtil.getEmployeeId(token))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "존재하지 않는 사용자입니다."));
        Set<Long> deviceIds = new HashSet<>(accessControlService.getAccessibleDeviceIds(user));
        Set<Long> zoneIds = accessControlService.getAccessibleZones(user).stream()
                .map(zone -> zone.getId())
                .collect(java.util.stream.Collectors.toSet());
        return sseService.subscribe(deviceIds, zoneIds);
    }
}
