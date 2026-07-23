package dev.bugi.sensor.alert.service;

import dev.bugi.sensor.alert.dto.AlarmEpisodeResponse;
import dev.bugi.sensor.alert.dto.AlarmEpisodeSsePayload;
import dev.bugi.sensor.alert.dto.EnrichmentClaim;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.alert.repository.AlertEnrichmentRepository;
import dev.bugi.sensor.sse.SseBroadcastEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * explain 결과 저장과 ENRICHED application event를 한 transaction에 묶는다.
 * 실제 SSE I/O는 SseBroadcastListener가 commit 성공 뒤 수행한다.
 */
@Service
@RequiredArgsConstructor
public class AlarmEpisodeEnrichmentService {

    private final AlertEnrichmentRepository enrichmentRepository;
    private final AlarmEpisodeRepository episodeRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public boolean complete(
            EnrichmentClaim claim, String evidence, String recommendation, Instant completedAt) {
        if (!enrichmentRepository.complete(claim, evidence, recommendation, completedAt)) {
            return false;
        }
        episodeRepository.findById(claim.episodeId()).ifPresent(episode -> {
            Long routeDeviceId = AlarmEpisodeService.routeDeviceId(episode);
            Long routeZoneId = AlarmEpisodeService.routeZoneId(episode);
            if (routeDeviceId != null || routeZoneId != null) {
                eventPublisher.publishEvent(new SseBroadcastEvent(
                        "alarm-episode",
                        routeDeviceId,
                        routeZoneId,
                        new AlarmEpisodeSsePayload(
                                AlarmEpisodeSsePayload.ChangeType.ENRICHED,
                                AlarmEpisodeResponse.from(episode, null))));
            }
        });
        return true;
    }
}
