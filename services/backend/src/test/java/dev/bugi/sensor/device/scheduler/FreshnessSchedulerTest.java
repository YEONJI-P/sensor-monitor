package dev.bugi.sensor.device.scheduler;

import dev.bugi.sensor.alert.service.AlarmLifecycleService;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.freshness.FreshnessDeviceState;
import dev.bugi.sensor.device.freshness.FreshnessPolicy;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService.OperatingDecision;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FreshnessSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
    private static final OperatingDecision ACTIVE =
            new OperatingDecision(true, false, null, "SCHEDULED_ACTIVE");

    @Mock DeviceStatusRepository deviceStatusRepository;
    @Mock OperatingCalendarService operatingCalendarService;
    @Mock AlarmLifecycleService alarmLifecycleService;
    @Spy Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    @InjectMocks FreshnessScheduler scheduler;

    @BeforeEach
    void defaults() {
        lenient().when(operatingCalendarService.evaluate(anySet(), eq(NOW)))
                .thenReturn(Map.of(1L, ACTIVE));
        lenient().when(operatingCalendarService.evaluateAlwaysOpenFallback())
                .thenReturn(ACTIVE);
    }

    @Test
    void expectedInterval_두배를_scheduler와_dashboard가_공유한다() {
        assertThat(FreshnessPolicy.evaluate(30, NOW.minusSeconds(60), NOW, ACTIVE))
                .isEqualTo(FreshnessPolicy.State.ONLINE);
        assertThat(FreshnessPolicy.evaluate(30, NOW.minusSeconds(61), NOW, ACTIVE))
                .isEqualTo(FreshnessPolicy.State.STALE);
    }

    @Test
    void zone별_판정과_episode_snapshot을_lifecycle에_넘긴다() {
        DeviceStatus first = status(1L, 10L, NOW.minusSeconds(61), 30);
        DeviceStatus second = status(2L, 10L, NOW.minusSeconds(120), 30);
        when(deviceStatusRepository.findAllWithDeviceAndZone())
                .thenReturn(List.of(first, second));

        scheduler.checkFreshness();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FreshnessDeviceState>> states =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(alarmLifecycleService).reconcileFreshnessZone(eq(10L), states.capture(),
                snapshot.capture());
        assertThat(states.getValue()).extracting(FreshnessDeviceState::state)
                .containsOnly(FreshnessPolicy.State.STALE);
        assertThat(snapshot.getValue()).containsEntry("zoneId", 10L)
                .containsEntry("staleDeviceIds", List.of(1L, 2L));
    }

    @Test
    void never_seen과_운영캘린더_억제도_episode를_열지_않도록_상태로_전달한다() {
        DeviceStatus neverSeen = status(1L, 10L, null, 30);
        DeviceStatus plannedOffline = status(2L, 10L, NOW.minusSeconds(120), 30);
        when(deviceStatusRepository.findAllWithDeviceAndZone())
                .thenReturn(List.of(neverSeen, plannedOffline));
        when(operatingCalendarService.evaluate(anySet(), eq(NOW))).thenReturn(Map.of(
                1L, new OperatingDecision(false, false, null, "PLANNED_OFFLINE")));

        scheduler.checkFreshness();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FreshnessDeviceState>> states =
                ArgumentCaptor.forClass(List.class);
        verify(alarmLifecycleService).reconcileFreshnessZone(eq(10L), states.capture(), any());
        assertThat(states.getValue()).extracting(FreshnessDeviceState::state)
                .containsOnly(FreshnessPolicy.State.PLANNED_OFFLINE);
    }

    @Test
    void status가_없으면_lifecycle을_호출하지_않는다() {
        when(deviceStatusRepository.findAllWithDeviceAndZone()).thenReturn(List.of());
        scheduler.checkFreshness();
        verifyNoInteractions(alarmLifecycleService);
    }

    private DeviceStatus status(long deviceId, long zoneId, Instant lastSeenAt,
                                int expectedIntervalSeconds) {
        Factory factory = mock(Factory.class);
        lenient().when(factory.getId()).thenReturn(1L);
        Zone zone = mock(Zone.class);
        lenient().when(zone.getId()).thenReturn(zoneId);
        lenient().when(zone.getName()).thenReturn("Z" + zoneId);
        lenient().when(zone.getFactory()).thenReturn(factory);
        Device device = mock(Device.class);
        lenient().when(device.getId()).thenReturn(deviceId);
        lenient().when(device.getCode()).thenReturn("D" + deviceId);
        lenient().when(device.getName()).thenReturn("device-" + deviceId);
        lenient().when(device.getExpectedIntervalSeconds()).thenReturn(expectedIntervalSeconds);
        lenient().when(device.getZone()).thenReturn(zone);
        DeviceStatus status = mock(DeviceStatus.class);
        lenient().when(status.getDeviceId()).thenReturn(deviceId);
        lenient().when(status.getDevice()).thenReturn(device);
        lenient().when(status.getLastSeenAt()).thenReturn(lastSeenAt);
        return status;
    }
}
