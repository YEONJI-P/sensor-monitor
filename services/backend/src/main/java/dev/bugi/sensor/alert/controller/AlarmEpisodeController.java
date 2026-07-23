package dev.bugi.sensor.alert.controller;

import dev.bugi.sensor.alert.dto.AlarmEpisodeResponse;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.entity.AlarmScopeType;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.service.AlarmEpisodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/alarm-episodes")
@RequiredArgsConstructor
public class AlarmEpisodeController {

    private final AlarmEpisodeService alarmEpisodeService;

    @GetMapping
    public ResponseEntity<Page<AlarmEpisodeResponse>> getEpisodes(
            @AuthenticationPrincipal String employeeId,
            @RequestParam(required = false) AlarmEpisodeStatus status,
            @RequestParam(required = false) AlarmType type,
            @RequestParam(required = false) AlarmScopeType scopeType,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long zoneId,
            @PageableDefault(size = 50, sort = "openedAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(alarmEpisodeService.getEpisodes(
                employeeId, status, type, scopeType, deviceId, channelId, zoneId, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlarmEpisodeResponse> getEpisode(
            @AuthenticationPrincipal String employeeId,
            @PathVariable Long id) {
        return ResponseEntity.ok(alarmEpisodeService.getEpisode(employeeId, id));
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<AlarmEpisodeResponse> acknowledge(
            @AuthenticationPrincipal String employeeId,
            @PathVariable Long id) {
        return ResponseEntity.ok(alarmEpisodeService.acknowledge(employeeId, id));
    }
}
