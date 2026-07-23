package dev.bugi.sensor.alert.scheduler;

import dev.bugi.sensor.alert.dto.EnrichmentClaim;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.repository.AlertEnrichmentRepository;
import dev.bugi.sensor.alert.service.AlarmEpisodeEnrichmentService;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.explain.client.ExplainClient;
import dev.bugi.sensor.explain.config.ExplainProperties;
import dev.bugi.sensor.explain.dto.AnomalyExplainRequest;
import dev.bugi.sensor.explain.dto.AnomalyExplainResponse;
import dev.bugi.sensor.explain.dto.FreshnessDiagnoseRequest;
import dev.bugi.sensor.explain.dto.FreshnessDiagnoseResponse;
import dev.bugi.sensor.sensordata.entity.SensorReading;
import dev.bugi.sensor.sensordata.failure.FailedReadingRepository;
import dev.bugi.sensor.sensordata.repository.SensorReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * episode snapshot을 claim한 뒤 transaction 밖에서 explain HTTP를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEnrichmentScheduler {

    private static final int CLAIM_BATCH = 20;
    private static final int WINDOW = 20;
    private static final int MIN_SAMPLES = 5;

    private final AlertEnrichmentRepository enrichmentRepository;
    private final AlarmEpisodeEnrichmentService alarmEpisodeEnrichmentService;
    private final SensorReadingRepository sensorReadingRepository;
    private final FailedReadingRepository failedReadingRepository;
    private final ExplainClient explainClient;
    private final ExplainProperties explainProperties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${explain.enrichment.fixed-delay-ms:30000}")
    public void enrichAlerts() {
        if (!explainProperties.isEnabled()) {
            return;
        }

        // claim()은 여기서 끝나는 짧은 transaction이다. 아래 HTTP 호출 동안 DB lease만 남는다.
        List<EnrichmentClaim> claims =
                enrichmentRepository.claim(CLAIM_BATCH, clock.instant());
        for (EnrichmentClaim claim : claims) {
            try {
                Diagnosis diagnosis = switch (claim.alarmType()) {
                    case THRESHOLD -> enrichThreshold(claim);
                    case DEVICE_SILENCE -> enrichFreshness(claim);
                    case ZONE_SILENCE -> throw new IllegalStateException(
                            "ZONE_SILENCE는 explain 대상이 아닙니다");
                };
                if (!alarmEpisodeEnrichmentService.complete(
                        claim, diagnosis.evidence(), diagnosis.recommendation(), clock.instant())) {
                    log.info("만료된 explain lease 결과 폐기 (alertId={})", claim.alertId());
                }
            } catch (Exception ex) {
                enrichmentRepository.fail(claim, clock.instant(), ex.getMessage());
                log.warn("explain 보강 실패 (alertId={}, attempt={}): {}",
                        claim.alertId(), claim.attempt(), ex.getMessage());
            }
        }
    }

    private Diagnosis enrichThreshold(EnrichmentClaim claim) {
        if (claim.sensorValue() == null || claim.channelId() == null) {
            throw new IllegalStateException("threshold explain projection 값이 부족합니다");
        }
        List<Double> recentValues = recentValues(claim.channelId());
        WindowMetrics metrics = WindowMetrics.of(
                recentValues, claim.thresholdValue(), claim.thresholdDirection());
        AnomalyExplainResponse response = explainClient.explainAnomaly(
                new AnomalyExplainRequest(
                        claim.deviceName(),
                        claim.sensorType(),
                        claim.unit(),
                        claim.sensorValue(),
                        claim.thresholdValue(),
                        claim.thresholdDirection(),
                        claim.message(),
                        recentValues.isEmpty() ? null : recentValues,
                        metrics.breachRate(),
                        metrics.trend(),
                        metrics.volatility()));
        return new Diagnosis(response.evidence(), response.recommendation());
    }

    private Diagnosis enrichFreshness(EnrichmentClaim claim) {
        if (claim.lastSeenAt() == null || claim.expectedIntervalSeconds() == null) {
            throw new IllegalStateException("freshness episode snapshot 값이 부족합니다");
        }
        int failedRecent = failedReadingRepository.countByDeviceIdAndCreatedAtAfter(
                claim.deviceId(), claim.lastSeenAt());
        FreshnessDiagnoseResponse response = explainClient.diagnoseFreshness(
                new FreshnessDiagnoseRequest(
                        claim.deviceName(),
                        claim.expectedIntervalSeconds(),
                        claim.lastSeenAt().toString(),
                        claim.elapsedSeconds(),
                        failedRecent));
        return new Diagnosis(response.report(), response.cause());
    }

    /** 채널의 최근 판독값을 시간순(과거→현재)으로 반환한다. */
    private List<Double> recentValues(Long channelId) {
        List<SensorReading> recent = sensorReadingRepository
                .findByChannelIdOrderByObservedAtDesc(channelId, PageRequest.of(0, WINDOW));
        List<Double> values = new ArrayList<>(recent.size());
        for (int i = recent.size() - 1; i >= 0; i--) {
            values.add(recent.get(i).getValue());
        }
        return values;
    }

    private record Diagnosis(String evidence, String recommendation) {
    }

    static record WindowMetrics(Double breachRate, Double trend, Double volatility) {

        private static final WindowMetrics EMPTY = new WindowMetrics(null, null, null);

        static WindowMetrics of(List<Double> values, Double threshold,
                                SensorChannel.ThresholdDirection direction) {
            if (values == null || values.size() < MIN_SAMPLES) {
                return EMPTY;
            }
            int n = values.size();
            Double breachRate = null;
            if (threshold != null) {
                SensorChannel.ThresholdDirection effectiveDirection = direction == null
                        ? SensorChannel.ThresholdDirection.ABOVE : direction;
                long breaches = values.stream().filter(value -> switch (effectiveDirection) {
                    case ABOVE -> value > threshold;
                    case BELOW -> value < threshold;
                    case ABS_ABOVE -> Math.abs(value) > threshold;
                }).count();
                breachRate = (double) breaches / n;
            }

            int half = n / 2;
            double front = average(values.subList(0, half));
            double back = average(values.subList(n - half, n));
            double trend = back - front;
            double mean = average(values);
            double variance = values.stream()
                    .mapToDouble(value -> (value - mean) * (value - mean))
                    .sum() / n;
            return new WindowMetrics(breachRate, trend, Math.sqrt(variance));
        }

        private static double average(List<Double> values) {
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }
}
