package dev.bugi.sensor.alert.service;

import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.entity.AlarmResolutionReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.service.AlarmLifecycleService.OpenOutcome;
import dev.bugi.sensor.alert.service.AlarmLifecycleService.OpenResult;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({AlarmLifecycleService.class, AlarmNotificationFactory.class})
class AlarmLifecycleServiceTest extends AbstractPostgresTest {

    @Autowired AlarmLifecycleService lifecycleService;
    @Autowired ChannelStatusRepository channelStatusRepository;

    @Test
    void threshold_open은_한_episode를_재사용하고_status_mirror와_watermark를_함께_전이한다() {
        Zone zone = persistZone(persistFactory("F"), "Z");
        Device device = tem.persist(Device.builder()
                .zone(zone).code("D-" + UUID.randomUUID()).name("D").expectedIntervalSeconds(10).build());
        SensorChannel channel = tem.persist(SensorChannel.builder()
                .device(device).code("temp").quantityKind("temperature").unit("C")
                .thresholdValue(80.0).thresholdDirection(ThresholdDirection.ABOVE).build());
        channelStatusRepository.save(new ChannelStatus(channel));
        tem.flush();

        Instant firstObserved = Instant.parse("2026-07-23T01:00:00Z");
        OpenResult openedResult = lifecycleService.openThresholdResult(
                channel.getId(), AlertSeverity.WARNING, firstObserved,
                Map.of("deviceCode", device.getCode(), "channelCode", channel.getCode()));
        OpenResult escalatedResult = lifecycleService.openThresholdResult(
                channel.getId(), AlertSeverity.CRITICAL, firstObserved.plusSeconds(1),
                Map.of("ignored", "episode snapshot is immutable"));
        AlarmEpisode opened = openedResult.episode();
        AlarmEpisode same = escalatedResult.episode();

        assertThat(openedResult.outcome()).isEqualTo(OpenOutcome.CREATED);
        assertThat(escalatedResult.outcome()).isEqualTo(OpenOutcome.SEVERITY_ESCALATED);
        assertThat(same.getId()).isEqualTo(opened.getId());
        assertThat(same.getMaxSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        ChannelStatus active = channelStatusRepository.findById(channel.getId()).orElseThrow();
        assertThat(active.isInAlarm()).isTrue();
        assertThat(active.getActiveEpisode().getId()).isEqualTo(opened.getId());
        assertThat(active.getLastEvaluatedObservedAt()).isEqualTo(firstObserved.plusSeconds(1));

        lifecycleService.resolveThreshold(
                channel.getId(), firstObserved.plusSeconds(2), AlarmResolutionReason.RECOVERED);

        assertThat(opened.getStatus()).isEqualTo(AlarmEpisodeStatus.RESOLVED);
        assertThat(active.isInAlarm()).isFalse();
        assertThat(active.getActiveEpisode()).isNull();
    }

    @Test
    void status행이_없으면_lazy_create로_경쟁하지_않고_명시적으로_실패한다() {
        Zone zone = persistZone(persistFactory("F-missing"), "Z");
        Device device = tem.persist(Device.builder()
                .zone(zone).code("D-" + UUID.randomUUID()).name("D").expectedIntervalSeconds(10).build());
        SensorChannel channel = tem.persist(SensorChannel.builder()
                .device(device).code("temp").quantityKind("temperature").unit("C")
                .thresholdValue(80.0).thresholdDirection(ThresholdDirection.ABOVE).build());
        tem.flush();

        assertThatThrownBy(() -> lifecycleService.openThreshold(
                channel.getId(), AlertSeverity.WARNING, Instant.parse("2026-07-23T01:00:00Z"),
                Map.of("channelCode", "temp")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel_status가 없습니다");
    }
}
