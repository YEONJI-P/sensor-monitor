package dev.bugi.sensor.alert.service;

import dev.bugi.sensor.alert.dto.AlarmEpisodeSsePayload;
import dev.bugi.sensor.alert.dto.EnrichmentClaim;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.entity.AlarmScopeType;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.alert.repository.AlertEnrichmentRepository;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.sse.SseBroadcastEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

class AlarmEpisodeEnrichmentServiceTest {

    @Test
    void 완료된_enrichment만_명시적_application_event를_발행한다() {
        AlertEnrichmentRepository enrichmentRepository = mock(AlertEnrichmentRepository.class);
        AlarmEpisodeRepository episodeRepository = mock(AlarmEpisodeRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        AlarmEpisodeEnrichmentService service = new AlarmEpisodeEnrichmentService(
                enrichmentRepository, episodeRepository, eventPublisher);
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        EnrichmentClaim claim = new EnrichmentClaim(
                1L, 2L, UUID.randomUUID(), 1, AlarmType.DEVICE_SILENCE,
                7L, null, "device", null, null, null, null, null,
                "message", 30, now.minusSeconds(60), 60);
        AlarmEpisode episode = mock(AlarmEpisode.class);
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(7L);
        when(episode.getId()).thenReturn(2L);
        when(episode.getDevice()).thenReturn(device);
        when(episode.getAlarmType()).thenReturn(AlarmType.DEVICE_SILENCE);
        when(episode.getScopeType()).thenReturn(AlarmScopeType.DEVICE);
        when(episode.getStatus()).thenReturn(AlarmEpisodeStatus.OPEN);
        when(episode.getCurrentSeverity()).thenReturn(AlertSeverity.CRITICAL);
        when(episode.getMaxSeverity()).thenReturn(AlertSeverity.CRITICAL);
        when(episode.getSnapshot()).thenReturn(Map.of());
        when(enrichmentRepository.complete(claim, "evidence", "recommendation", now))
                .thenReturn(true);
        when(episodeRepository.findById(2L)).thenReturn(Optional.of(episode));

        assertThat(service.complete(claim, "evidence", "recommendation", now)).isTrue();

        var captor = forClass(SseBroadcastEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().event()).isEqualTo("alarm-episode");
        assertThat(((AlarmEpisodeSsePayload) captor.getValue().payload()).changeType())
                .isEqualTo(AlarmEpisodeSsePayload.ChangeType.ENRICHED);
    }
}
