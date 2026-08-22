package dev.bugi.sensor.dashboard.service;

import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.dashboard.dto.DashboardOverviewResponse;
import dev.bugi.sensor.dashboard.dto.DashboardOverviewResponse.ChannelOverview;
import dev.bugi.sensor.dashboard.dto.DashboardOverviewResponse.DeviceOverview;
import dev.bugi.sensor.dashboard.dto.DashboardOverviewResponse.FactoryOverview;
import dev.bugi.sensor.dashboard.dto.DashboardOverviewResponse.Freshness;
import dev.bugi.sensor.dashboard.dto.DashboardOverviewResponse.ZoneOverview;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.freshness.FreshnessPolicy;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.device.repository.SensorChannelRepository;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService;
import dev.bugi.sensor.factory.calendar.service.OperatingCalendarService.OperatingDecision;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sensordata.anomaly.ThresholdDetector;
import dev.bugi.sensor.sensordata.repository.SensorReadingRepository;
import dev.bugi.sensor.sensordata.repository.SensorReadingRepository.LatestReadingProjection;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;

@Service
@RequiredArgsConstructor
public class DashboardOverviewService {

    private static final String UNASSIGNED_FACTORY = "미지정 공장";
    private static final String UNASSIGNED_ZONE = "미지정 구역";
    private static final EnumSet<Role> ALLOWED_ROLES =
            EnumSet.of(Role.SYSTEM_ADMIN, Role.SYSTEM_VIEWER, Role.FACTORY_ADMIN, Role.MEMBER, Role.VIEWER);

    private final AccessControlService accessControlService;
    private final DeviceRepository deviceRepository;
    private final DeviceStatusRepository deviceStatusRepository;
    private final SensorChannelRepository sensorChannelRepository;
    private final ChannelStatusRepository channelStatusRepository;
    private final AlarmEpisodeRepository alarmEpisodeRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final ThresholdDetector thresholdDetector;
    private final OperatingCalendarService operatingCalendarService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DashboardOverviewResponse getOverview(String employeeId) {
        Instant generatedAt = clock.instant();
        User user = accessControlService.getUser(employeeId);
        if (!ALLOWED_ROLES.contains(user.getRole())) {
            throw new AccessDeniedException("대시보드 접근 권한이 없어요");
        }
        List<Long> accessibleDeviceIds = accessControlService.getAccessibleDeviceIds(user);
        if (accessibleDeviceIds.isEmpty()) {
            return new DashboardOverviewResponse(generatedAt, List.of());
        }

        List<Device> devices = deviceRepository.findOverviewDevicesByIdIn(accessibleDeviceIds);
        if (devices.isEmpty()) {
            return new DashboardOverviewResponse(generatedAt, List.of());
        }
        List<Long> deviceIds = devices.stream().map(Device::getId).toList();
        Map<Long, DeviceStatus> deviceStatuses = deviceStatusRepository.findAllById(deviceIds).stream()
                .collect(Collectors.toMap(DeviceStatus::getDeviceId, Function.identity()));

        List<SensorChannel> channels = sensorChannelRepository.findByDeviceIdInWithDeviceAndZone(deviceIds);
        List<Long> channelIds = channels.stream().map(SensorChannel::getId).toList();
        Map<Long, ChannelStatus> channelStatuses = channelIds.isEmpty()
                ? Map.of()
                : channelStatusRepository.findAllById(channelIds).stream()
                .collect(Collectors.toMap(ChannelStatus::getChannelId, Function.identity()));
        Map<Long, LatestReadingProjection> latestReadings = channelIds.isEmpty()
                ? Map.of()
                : sensorReadingRepository.findLatestByChannelIds(channelIds).stream()
                .collect(Collectors.toMap(LatestReadingProjection::getChannelId, Function.identity()));

        List<Long> zoneIds = devices.stream()
                .map(Device::getZone).filter(java.util.Objects::nonNull)
                .map(Zone::getId).distinct().toList();
        List<AlarmEpisode> activeEpisodes = alarmEpisodeRepository.findAll(
                activeEpisodeSpecification(deviceIds, zoneIds));
        Map<Long, Long> activeEpisodeCountByDevice = new LinkedHashMap<>();
        Map<Long, Long> activeEpisodeIdByChannel = new LinkedHashMap<>();
        Map<Long, List<Long>> deviceIdsByZone = devices.stream()
                .filter(device -> device.getZone() != null)
                .collect(Collectors.groupingBy(device -> device.getZone().getId(),
                        LinkedHashMap::new,
                        Collectors.mapping(Device::getId, Collectors.toList())));
        for (AlarmEpisode episode : activeEpisodes) {
            if (episode.getDevice() != null) {
                activeEpisodeCountByDevice.merge(episode.getDevice().getId(), 1L, Long::sum);
            } else if (episode.getZone() != null) {
                for (Long affectedDeviceId :
                        deviceIdsByZone.getOrDefault(episode.getZone().getId(), List.of())) {
                    activeEpisodeCountByDevice.merge(affectedDeviceId, 1L, Long::sum);
                }
            }
            if (episode.getChannel() != null) {
                activeEpisodeIdByChannel.put(episode.getChannel().getId(), episode.getId());
            }
        }

        Map<Long, List<SensorChannel>> channelsByDevice = channels.stream()
                .collect(Collectors.groupingBy(c -> c.getDevice().getId(), LinkedHashMap::new, Collectors.toList()));

        List<Long> factoryIds = devices.stream()
                .map(Device::getZone).filter(java.util.Objects::nonNull)
                .map(Zone::getFactory).filter(java.util.Objects::nonNull)
                .map(Factory::getId).distinct().toList();
        Map<Long, OperatingDecision> operatingDecisions = operatingCalendarService.evaluate(factoryIds, generatedAt);

        Map<GroupKey, FactoryGroup> factoryGroups = new LinkedHashMap<>();
        for (Device device : devices) {
            Zone zone = device.getZone();
            Factory factory = zone != null ? zone.getFactory() : null;
            GroupKey factoryKey = new GroupKey(factory != null ? factory.getId() : null,
                    factory != null ? factory.getName() : UNASSIGNED_FACTORY);
            GroupKey zoneKey = new GroupKey(zone != null ? zone.getId() : null,
                    zone != null ? zone.getName() : UNASSIGNED_ZONE);

            DeviceStatus deviceStatus = deviceStatuses.get(device.getId());
            Instant lastSeenAt = deviceStatus != null ? deviceStatus.getLastSeenAt() : null;
            List<ChannelOverview> channelOverviews = channelsByDevice
                    .getOrDefault(device.getId(), List.of()).stream()
                    .map(channel -> toChannelOverview(channel, channelStatuses.get(channel.getId()),
                            latestReadings.get(channel.getId()),
                            activeEpisodeIdByChannel.get(channel.getId())))
                    .toList();
            int alarmCount = (int) channelOverviews.stream().filter(ChannelOverview::inAlarm).count();
            OperatingDecision operatingDecision = factory == null
                    ? operatingCalendarService.evaluateAlwaysOpenFallback()
                    : operatingDecisions.getOrDefault(factory.getId(),
                    operatingCalendarService.evaluateAlwaysOpenFallback());
            DeviceOverview deviceOverview = new DeviceOverview(
                    device.getId(), device.getCode(), device.getName(), device.getLocation(),
                    device.getExpectedIntervalSeconds(), lastSeenAt,
                    freshness(device.getExpectedIntervalSeconds(), lastSeenAt, generatedAt, operatingDecision),
                    alarmCount, channelOverviews,
                    activeEpisodeCountByDevice.getOrDefault(device.getId(), 0L));

            factoryGroups.computeIfAbsent(factoryKey, FactoryGroup::new)
                    .zones.computeIfAbsent(zoneKey, ZoneGroup::new)
                    .devices.add(deviceOverview);
        }

        List<FactoryOverview> factories = factoryGroups.values().stream()
                .map(FactoryGroup::toResponse)
                .toList();
        return new DashboardOverviewResponse(generatedAt, factories);
    }

    private ChannelOverview toChannelOverview(SensorChannel channel, ChannelStatus status,
                                               LatestReadingProjection latest,
                                               Long activeEpisodeId) {
        Double value = latest != null ? latest.getValue() : null;
        return new ChannelOverview(
                channel.getId(), channel.getCode(), channel.getUnit(), channel.getQuantityKind(),
                value,
                latest != null ? latest.getObservedAt() : null,
                latest != null ? latest.getReceivedAt() : null,
                value != null && thresholdDetector.isAnomaly(channel, value),
                status != null && status.isInAlarm(),
                status != null ? status.getLastAlertAt() : null,
                channel.getThresholdValue(), channel.getThresholdDirection(),
                activeEpisodeId);
    }

    private static Specification<AlarmEpisode> activeEpisodeSpecification(
            List<Long> deviceIds, List<Long> zoneIds) {
        return (root, query, builder) -> {
            var open = builder.equal(root.get("status"), AlarmEpisodeStatus.OPEN);
            var deviceScope = root.get("device").get("id").in(deviceIds);
            if (zoneIds.isEmpty()) {
                return builder.and(open, deviceScope);
            }
            return builder.and(open, builder.or(
                    deviceScope, root.get("zone").get("id").in(zoneIds)));
        };
    }

    static Freshness freshness(Integer expectedIntervalSeconds, Instant lastSeenAt, Instant now,
                               OperatingDecision operatingDecision) {
        return switch (FreshnessPolicy.evaluate(
                expectedIntervalSeconds, lastSeenAt, now, operatingDecision)) {
            case NOT_MONITORED -> Freshness.NOT_MONITORED;
            case PLANNED_OFFLINE -> Freshness.PLANNED_OFFLINE;
            case RESUME_GRACE -> Freshness.RESUMING;
            case NEVER_SEEN -> Freshness.NEVER_SEEN;
            case ONLINE -> Freshness.ONLINE;
            case STALE -> Freshness.STALE;
        };
    }

    private record GroupKey(Long id, String name) {
    }

    private static final class FactoryGroup {
        private final GroupKey key;
        private final Map<GroupKey, ZoneGroup> zones = new LinkedHashMap<>();

        private FactoryGroup(GroupKey key) {
            this.key = key;
        }

        private FactoryOverview toResponse() {
            return new FactoryOverview(key.id(), key.name(), zones.values().stream()
                    .map(ZoneGroup::toResponse).toList());
        }
    }

    private static final class ZoneGroup {
        private final GroupKey key;
        private final List<DeviceOverview> devices = new ArrayList<>();

        private ZoneGroup(GroupKey key) {
            this.key = key;
        }

        private ZoneOverview toResponse() {
            return new ZoneOverview(key.id(), key.name(), List.copyOf(devices));
        }
    }
}
