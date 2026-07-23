package dev.bugi.sensor.alert.repository;

import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.entity.AlarmResolutionReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.support.AbstractPostgresTest;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.user.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmEpisodeRepositoryTest extends AbstractPostgresTest {

    @Autowired AlarmEpisodeRepository episodeRepository;
    @Autowired AlarmAcknowledgementRepository acknowledgementRepository;

    @Test
    void threshold_episode의_JSON_snapshot과_transition과_ack_이력을_보존한다() {
        Factory factory = persistFactory("F");
        Zone zone = persistZone(factory, "Z");
        Device device = tem.persist(Device.builder()
                .zone(zone).code("D-" + UUID.randomUUID()).name("Device")
                .expectedIntervalSeconds(10).build());
        SensorChannel channel = tem.persist(SensorChannel.builder()
                .device(device).code("temp").quantityKind("temperature").unit("C")
                .thresholdValue(80.0).thresholdDirection(ThresholdDirection.ABOVE).build());
        User user = tem.persist(User.builder()
                .employeeId("U-" + UUID.randomUUID()).name("Operator")
                .factory(factory).role(Role.MEMBER).status(UserStatus.ACTIVE).build());

        Instant openedAt = Instant.parse("2026-07-23T01:00:00Z");
        AlarmEpisode episode = episodeRepository.save(AlarmEpisode.openThreshold(
                device, channel, AlertSeverity.WARNING, openedAt,
                Map.of("deviceCode", device.getCode(), "channelCode", channel.getCode(),
                        "thresholdValue", 80.0)));
        episode.transitionSeverity(AlertSeverity.CRITICAL);
        episode.updateDiagnosis("evidence", "recommendation");
        episode.markNotified(openedAt.plusSeconds(10));
        acknowledgementRepository.save(new AlarmAcknowledgement(
                episode, user, episode.getCurrentSeverity(), openedAt.plusSeconds(20)));
        episode.resolve(openedAt.plusSeconds(30), AlarmResolutionReason.RECOVERED);
        tem.flush();
        tem.clear();

        AlarmEpisode reloaded = episodeRepository.findById(episode.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AlarmEpisodeStatus.RESOLVED);
        assertThat(reloaded.getCurrentSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(reloaded.getMaxSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(reloaded.getResolutionReason()).isEqualTo(AlarmResolutionReason.RECOVERED);
        assertThat(reloaded.getSnapshot())
                .containsEntry("deviceCode", device.getCode())
                .containsEntry("thresholdValue", 80.0);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();

        assertThat(acknowledgementRepository
                .findByEpisodeIdOrderByCreatedAtDesc(episode.getId()))
                .singleElement()
                .satisfies(ack -> {
                    assertThat(ack.getAckSeverity()).isEqualTo(AlertSeverity.CRITICAL);
                    assertThat(ack.getUser().getEmployeeId()).isEqualTo(user.getEmployeeId());
                });
    }
}
