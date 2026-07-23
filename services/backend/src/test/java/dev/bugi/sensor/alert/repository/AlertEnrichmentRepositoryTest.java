package dev.bugi.sensor.alert.repository;

import dev.bugi.sensor.alert.dto.EnrichmentClaim;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.Alert;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.SensorChannelRepository;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.repository.FactoryRepository;
import dev.bugi.sensor.factory.repository.ZoneRepository;
import dev.bugi.sensor.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@Import(AlertEnrichmentRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertEnrichmentRepositoryTest extends AbstractPostgresTest {

    private static final Instant NOW = Instant.parse("2026-07-23T03:00:00Z");

    @Autowired AlertEnrichmentRepository enrichmentRepository;
    @Autowired AlarmEpisodeRepository episodeRepository;
    @Autowired AlertRepository alertRepository;
    @Autowired FactoryRepository factoryRepository;
    @Autowired ZoneRepository zoneRepository;
    @Autowired DeviceRepository deviceRepository;
    @Autowired SensorChannelRepository channelRepository;

    @Test
    void 두_worker가_동시에_claim해도_episode는_한번만_소유한다() {
        persistThresholdAlert("concurrent-" + UUID.randomUUID());

        CompletableFuture<List<EnrichmentClaim>> first = CompletableFuture.supplyAsync(
                () -> enrichmentRepository.claim(10, NOW));
        CompletableFuture<List<EnrichmentClaim>> second = CompletableFuture.supplyAsync(
                () -> enrichmentRepository.claim(10, NOW));
        List<EnrichmentClaim> all = CompletableFuture.allOf(first, second)
                .thenApply(ignored -> java.util.stream.Stream
                        .concat(first.join().stream(), second.join().stream()).toList())
                .join();

        assertThat(all).hasSize(1);
        EnrichmentClaim claim = all.get(0);
        assertThat(enrichmentRepository.complete(claim, "e", "r", NOW.plusSeconds(1)))
                .isTrue();
    }

    @Test
    void lease가_만료되면_다른_worker가_회수하고_오래된_token은_결과를_쓸수없다() {
        persistThresholdAlert("lease-" + UUID.randomUUID());
        EnrichmentClaim first = enrichmentRepository.claim(1, NOW).get(0);

        assertThat(enrichmentRepository.claim(1, NOW.plusSeconds(119))).isEmpty();
        EnrichmentClaim recovered = enrichmentRepository.claim(1, NOW.plusSeconds(121)).get(0);

        assertThat(recovered.alertId()).isEqualTo(first.alertId());
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(enrichmentRepository.complete(first, "stale", "stale", NOW.plusSeconds(122)))
                .isFalse();
        assertThat(enrichmentRepository.complete(recovered, "fresh", "fresh", NOW.plusSeconds(123)))
                .isTrue();
    }

    @Test
    void 실패는_지수_backoff_후에만_재claim된다() {
        persistThresholdAlert("backoff-" + UUID.randomUUID());
        EnrichmentClaim first = enrichmentRepository.claim(1, NOW).get(0);

        assertThat(enrichmentRepository.fail(first, NOW, "explain down")).isTrue();
        assertThat(enrichmentRepository.claim(1, NOW.plusSeconds(29))).isEmpty();
        EnrichmentClaim retry = enrichmentRepository.claim(1, NOW.plusSeconds(30)).get(0);
        assertThat(retry.attempt()).isEqualTo(2);
        enrichmentRepository.complete(retry, "e", "r", NOW.plusSeconds(31));
    }

    @Test
    void projection은_변경된_현재설정이_아니라_episode_snapshot을_사용한다() {
        Persisted persisted = persistThresholdAlert("snapshot-" + UUID.randomUUID());
        persisted.device().update("current-device", null, 999);
        persisted.channel().update("K", "current-kind", 999.0, ThresholdDirection.BELOW);
        deviceRepository.save(persisted.device());
        channelRepository.save(persisted.channel());

        EnrichmentClaim claim = enrichmentRepository.claim(1, NOW).get(0);

        assertThat(claim.deviceName()).isEqualTo("snapshot-device");
        assertThat(claim.sensorType()).isEqualTo("snapshot-kind");
        assertThat(claim.unit()).isEqualTo("snapshot-unit");
        assertThat(claim.thresholdValue()).isEqualTo(100.0);
        assertThat(claim.thresholdDirection()).isEqualTo(ThresholdDirection.ABOVE);
        enrichmentRepository.complete(claim, "e", "r", NOW.plusSeconds(1));
    }

    private Persisted persistThresholdAlert(String code) {
        Factory factory = factoryRepository.save(
                Factory.builder().name("F-" + code).build());
        Zone zone = zoneRepository.save(
                Zone.builder().factory(factory).name("Z-" + code).build());
        Device device = deviceRepository.save(Device.builder()
                .zone(zone).code("D-" + code).name("current-before-change")
                .expectedIntervalSeconds(30).build());
        SensorChannel channel = channelRepository.save(SensorChannel.builder()
                .device(device).code("C-" + code).quantityKind("current-before-change")
                .unit("X").thresholdValue(80.0)
                .thresholdDirection(ThresholdDirection.BELOW).build());
        AlarmEpisode episode = episodeRepository.save(AlarmEpisode.openThreshold(
                device, channel, AlertSeverity.WARNING, NOW.minusSeconds(10), Map.of(
                        "deviceName", "snapshot-device",
                        "quantityKind", "snapshot-kind",
                        "unit", "snapshot-unit",
                        "sensorValue", 120.0,
                        "thresholdValue", 100.0,
                        "thresholdDirection", "ABOVE")));
        Alert alert = alertRepository.save(Alert.builder()
                .episode(episode).device(device).channel(channel)
                .notificationReason(AlertNotificationReason.INITIAL)
                .sensorValue(120.0).thresholdValue(80.0)
                .message("threshold").severity(AlertSeverity.WARNING).build());
        return new Persisted(device, channel, episode, alert);
    }

    private record Persisted(Device device, SensorChannel channel,
                             AlarmEpisode episode, Alert alert) {
    }
}
