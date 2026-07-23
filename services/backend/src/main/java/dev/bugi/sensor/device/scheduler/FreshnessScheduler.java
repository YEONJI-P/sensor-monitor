package dev.bugi.sensor.device.scheduler;

import dev.bugi.sensor.alert.service.AlarmLifecycleService;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.freshness.FreshnessDeviceState;
import dev.bugi.sensor.device.freshness.FreshnessPolicy;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService.OperatingDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 기대 수신 주기의 두 배를 넘긴 장치를 zone 단위로 episode에 반영한다.
 *
 * 판정용 읽기는 transaction 밖에서 하고, 실제 전이는 AlarmLifecycleService의 짧은
 * zone 직렬화 transaction에 위임한다. explain 호출은 별도 enrichment scheduler가 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreshnessScheduler {

    private final DeviceStatusRepository deviceStatusRepository;
    private final OperatingCalendarService operatingCalendarService;
    private final AlarmLifecycleService alarmLifecycleService;
    private final Clock clock;

    @Scheduled(fixedRateString = "${freshness.scheduler.fixed-rate-ms:60000}")
    public void checkFreshness() {
        List<DeviceStatus> statuses = deviceStatusRepository.findAllWithDeviceAndZone();
        if (statuses.isEmpty()) {
            return;
        }

        Instant now = clock.instant();
        Set<Long> factoryIds = statuses.stream()
                .map(status -> status.getDevice().getZone().getFactory().getId())
                .collect(Collectors.toSet());
        Map<Long, OperatingDecision> decisions =
                operatingCalendarService.evaluate(factoryIds, now);

        Map<Long, List<FreshnessDeviceState>> statesByZone = new LinkedHashMap<>();
        Map<Long, DeviceStatus> statusesById = new LinkedHashMap<>();
        statuses.stream()
                .sorted(Comparator
                        .comparing((DeviceStatus status) -> status.getDevice().getZone().getId())
                        .thenComparing(DeviceStatus::getDeviceId))
                .forEach(status -> {
                    Device device = status.getDevice();
                    Long factoryId = device.getZone().getFactory().getId();
                    OperatingDecision decision = decisions.getOrDefault(
                            factoryId, operatingCalendarService.evaluateAlwaysOpenFallback());
                    FreshnessPolicy.State state = FreshnessPolicy.evaluate(
                            device.getExpectedIntervalSeconds(), status.getLastSeenAt(), now, decision);
                    statesByZone.computeIfAbsent(device.getZone().getId(), ignored -> new ArrayList<>())
                            .add(new FreshnessDeviceState(
                                    device.getId(), device.getExpectedIntervalSeconds(),
                                    status.getLastSeenAt(), state,
                                    deviceSnapshot(status, state, now)));
                    statusesById.put(device.getId(), status);
                });

        for (Map.Entry<Long, List<FreshnessDeviceState>> entry : statesByZone.entrySet()) {
            Long zoneId = entry.getKey();
            List<FreshnessDeviceState> zoneStates = entry.getValue();
            try {
                alarmLifecycleService.reconcileFreshnessZone(
                        zoneId, zoneStates, zoneSnapshot(zoneId, zoneStates, statusesById, now));
            } catch (RuntimeException ex) {
                log.error("freshness zone reconcile 실패 (zoneId={}): {}",
                        zoneId, ex.getMessage(), ex);
            }
        }
    }

    private static Map<String, Object> deviceSnapshot(DeviceStatus status,
                                                      FreshnessPolicy.State state,
                                                      Instant now) {
        Device device = status.getDevice();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("deviceId", device.getId());
        snapshot.put("deviceCode", device.getCode());
        snapshot.put("deviceName", device.getName());
        snapshot.put("zoneId", device.getZone().getId());
        snapshot.put("zoneName", device.getZone().getName());
        snapshot.put("expectedIntervalSeconds", device.getExpectedIntervalSeconds());
        snapshot.put("lastSeenAt",
                status.getLastSeenAt() == null ? null : status.getLastSeenAt().toString());
        snapshot.put("elapsedSeconds", status.getLastSeenAt() == null
                ? null : FreshnessPolicy.elapsedSeconds(status.getLastSeenAt(), now));
        snapshot.put("evaluatedAt", now.toString());
        snapshot.put("freshnessState", state.name());
        return snapshot;
    }

    private static Map<String, Object> zoneSnapshot(
            Long zoneId, List<FreshnessDeviceState> states,
            Map<Long, DeviceStatus> statusesById, Instant now) {
        DeviceStatus representative = statusesById.get(states.get(0).deviceId());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("zoneId", zoneId);
        snapshot.put("zoneName", representative.getDevice().getZone().getName());
        snapshot.put("factoryId", representative.getDevice().getZone().getFactory().getId());
        snapshot.put("evaluatedAt", now.toString());
        snapshot.put("deviceCount", states.size());
        snapshot.put("deviceIds", states.stream().map(FreshnessDeviceState::deviceId).toList());
        snapshot.put("staleDeviceIds", states.stream()
                .filter(state -> state.state() == FreshnessPolicy.State.STALE)
                .map(FreshnessDeviceState::deviceId)
                .toList());
        snapshot.put("policyFingerprint", states.stream()
                .map(state -> state.deviceId() + ":"
                        + (state.expectedIntervalSeconds() == null
                        ? "NOT_MONITORED" : state.expectedIntervalSeconds()))
                .collect(Collectors.joining(",")));
        snapshot.put("devices", states.stream().map(FreshnessDeviceState::snapshot).toList());
        return snapshot;
    }
}
