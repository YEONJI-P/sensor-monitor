package dev.bugi.sensor.alert.scheduler;

import dev.bugi.sensor.alert.dto.EnrichmentClaim;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.repository.AlertEnrichmentRepository;
import dev.bugi.sensor.alert.service.AlarmEpisodeEnrichmentService;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import dev.bugi.sensor.explain.client.ExplainClient;
import dev.bugi.sensor.explain.config.ExplainProperties;
import dev.bugi.sensor.explain.dto.AnomalyExplainRequest;
import dev.bugi.sensor.explain.dto.AnomalyExplainResponse;
import dev.bugi.sensor.explain.dto.FreshnessDiagnoseRequest;
import dev.bugi.sensor.explain.dto.FreshnessDiagnoseResponse;
import dev.bugi.sensor.sensordata.entity.SensorReading;
import dev.bugi.sensor.sensordata.failure.FailedReadingRepository;
import dev.bugi.sensor.sensordata.repository.SensorReadingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertEnrichmentSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Mock AlertEnrichmentRepository enrichmentRepository;
    @Mock AlarmEpisodeEnrichmentService alarmEpisodeEnrichmentService;
    @Mock SensorReadingRepository sensorReadingRepository;
    @Mock FailedReadingRepository failedReadingRepository;
    @Mock ExplainClient explainClient;
    @Mock ExplainProperties explainProperties;
    @Spy Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    @Captor ArgumentCaptor<AnomalyExplainRequest> anomalyRequest;
    @Captor ArgumentCaptor<FreshnessDiagnoseRequest> freshnessRequest;
    @InjectMocks AlertEnrichmentScheduler scheduler;

    @Test
    void threshold_claim을_snapshot_projection으로_보강하고_lease를_완료한다() {
        EnrichmentClaim claim = thresholdClaim();
        when(explainProperties.isEnabled()).thenReturn(true);
        when(enrichmentRepository.claim(20, NOW)).thenReturn(List.of(claim));
        when(sensorReadingRepository.findByChannelIdOrderByObservedAtDesc(eq(7L), any()))
                .thenReturn(List.of(reading(130), reading(120), reading(110),
                        reading(90), reading(80)));
        when(explainClient.explainAnomaly(any()))
                .thenReturn(new AnomalyExplainResponse("근거", "권고", "WARNING", "echo"));
        when(alarmEpisodeEnrichmentService.complete(claim, "근거", "권고", NOW)).thenReturn(true);

        scheduler.enrichAlerts();

        verify(explainClient).explainAnomaly(anomalyRequest.capture());
        assertThat(anomalyRequest.getValue().deviceName()).isEqualTo("snapshot-device");
        assertThat(anomalyRequest.getValue().breachRate()).isEqualTo(0.6, within(1e-9));
        verify(alarmEpisodeEnrichmentService).complete(claim, "근거", "권고", NOW);
    }

    @Test
    void freshness_claim은_episode_snapshot과_실패건수로_비동기_진단한다() {
        EnrichmentClaim claim = freshnessClaim();
        when(explainProperties.isEnabled()).thenReturn(true);
        when(enrichmentRepository.claim(20, NOW)).thenReturn(List.of(claim));
        when(failedReadingRepository.countByDeviceIdAndCreatedAtAfter(
                3L, NOW.minusSeconds(90))).thenReturn(4);
        when(explainClient.diagnoseFreshness(any())).thenReturn(
                new FreshnessDiagnoseResponse("게이트웨이 확인", "수신 단절 근거", "echo"));
        when(alarmEpisodeEnrichmentService.complete(
                claim, "수신 단절 근거", "게이트웨이 확인", NOW)).thenReturn(true);

        scheduler.enrichAlerts();

        verify(explainClient).diagnoseFreshness(freshnessRequest.capture());
        assertThat(freshnessRequest.getValue().expectedIntervalSeconds()).isEqualTo(30);
        assertThat(freshnessRequest.getValue().failedReadingRecentCount()).isEqualTo(4);
        verify(alarmEpisodeEnrichmentService).complete(
                claim, "수신 단절 근거", "게이트웨이 확인", NOW);
    }

    @Test
    void HTTP_실패는_lease를_풀고_backoff로_넘긴다() {
        EnrichmentClaim claim = thresholdClaim();
        when(explainProperties.isEnabled()).thenReturn(true);
        when(enrichmentRepository.claim(20, NOW)).thenReturn(List.of(claim));
        when(sensorReadingRepository.findByChannelIdOrderByObservedAtDesc(eq(7L), any()))
                .thenReturn(List.of());
        when(explainClient.explainAnomaly(any())).thenThrow(new RuntimeException("down"));

        scheduler.enrichAlerts();

        verify(enrichmentRepository).fail(claim, NOW, "down");
        verify(alarmEpisodeEnrichmentService, never()).complete(
                eq(claim), any(), any(), any());
    }

    private EnrichmentClaim thresholdClaim() {
        return new EnrichmentClaim(1L, 11L, UUID.randomUUID(), 1,
                AlarmType.THRESHOLD, 3L, 7L, "snapshot-device",
                "temperature", "C", 120.0, 100.0, ThresholdDirection.ABOVE,
                "임계 이탈", null, null, null);
    }

    private EnrichmentClaim freshnessClaim() {
        return new EnrichmentClaim(2L, 12L, UUID.randomUUID(), 1,
                AlarmType.DEVICE_SILENCE, 3L, null, "snapshot-device",
                null, null, null, null, null, "수신 끊김",
                30, NOW.minusSeconds(90), 90);
    }

    private SensorReading reading(double value) {
        return SensorReading.builder().value(value).build();
    }
}
