package dev.bugi.sensor.alert.service;

import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmResolutionReason;
import dev.bugi.sensor.alert.entity.Alert;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.repository.AlarmAcknowledgementRepository;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.freshness.FreshnessDeviceState;
import dev.bugi.sensor.device.freshness.FreshnessPolicy;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.repository.ZoneRepository;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.bugi.sensor.alert.dto.AlertResponse;
import dev.bugi.sensor.alert.dto.AlarmEpisodeResponse;
import dev.bugi.sensor.alert.dto.AlarmEpisodeSsePayload;
import dev.bugi.sensor.sse.SseBroadcastEvent;

/**
 * detector와 scheduler가 공유하는 짧은 DB transaction 경계.
 * 외부 HTTP 호출은 받지 않으며 repository lock + entity transition만 조정한다.
 */
@Service
@RequiredArgsConstructor
public class AlarmLifecycleService {

    private static final int ZONE_COHORT_MIN = 2;
    private static final Duration REMINDER_INTERVAL = Duration.ofMinutes(5);

    private final AlarmEpisodeRepository episodeRepository;
    private final AlarmAcknowledgementRepository acknowledgementRepository;
    private final ChannelStatusRepository channelStatusRepository;
    private final DeviceStatusRepository deviceStatusRepository;
    private final ZoneRepository zoneRepository;
    private final UserRepository userRepository;
    private final AlarmNotificationFactory notificationFactory;
    private final dev.bugi.sensor.alert.repository.AlertRepository alertRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public AlarmEpisode openThreshold(Long channelId, AlertSeverity severity,
                                      Instant observedAt, Map<String, Object> snapshot) {
        return openThresholdResult(channelId, severity, observedAt, snapshot).episode();
    }

    @Transactional
    public OpenResult openThresholdResult(Long channelId, AlertSeverity severity,
                                          Instant observedAt, Map<String, Object> snapshot) {
        ChannelStatus status = lockChannelStatus(channelId);
        if (!status.shouldEvaluate(observedAt, null)) {
            AlarmEpisode episode = episodeRepository.findOpenThresholdForUpdate(channelId)
                    .orElseThrow(() -> new IllegalStateException(
                            "역행 관측인데 활성 threshold episode가 없습니다"));
            return new OpenResult(episode, OpenOutcome.EXISTING);
        }
        Optional<AlarmEpisode> existing = episodeRepository.findOpenThresholdForUpdate(channelId);
        if (existing.isPresent()) {
            AlertSeverity previousSeverity = existing.get().getCurrentSeverity();
            existing.get().transitionSeverity(severity);
            status.markEvaluated(observedAt, null);
            status.mirror(existing.get(), status.getLastAlertAt());
            return new OpenResult(existing.get(),
                    openOutcome(previousSeverity, severity));
        }
        SensorChannel channel = status.getChannel();
        AlarmEpisode episode = episodeRepository.save(
                AlarmEpisode.openThreshold(channel.getDevice(), channel, severity,
                        clock.instant(), snapshot));
        publishEpisode(AlarmEpisodeSsePayload.ChangeType.OPEN, episode);
        status.markEvaluated(observedAt, null);
        status.enterAlarm(episode, clock.instant());
        return new OpenResult(episode, OpenOutcome.CREATED);
    }

    @Transactional
    public Optional<AlarmEpisode> resolveThreshold(Long channelId, Instant observedAt,
                                                   AlarmResolutionReason reason) {
        ChannelStatus status = lockChannelStatus(channelId);
        if (!status.shouldEvaluate(observedAt, null)) {
            return episodeRepository.findOpenThresholdForUpdate(channelId);
        }
        Optional<AlarmEpisode> episode = episodeRepository.findOpenThresholdForUpdate(channelId);
        episode.ifPresent(value -> resolveAndPublish(value, clock.instant(), reason));
        status.markEvaluated(observedAt, null);
        status.clearAlarm();
        return episode;
    }

    @Transactional
    public AlarmEpisode openDeviceSilence(Long deviceId, AlertSeverity severity,
                                          Map<String, Object> snapshot) {
        return openDeviceSilenceResult(deviceId, severity, snapshot).episode();
    }

    @Transactional
    public OpenResult openDeviceSilenceResult(Long deviceId, AlertSeverity severity,
                                              Map<String, Object> snapshot) {
        DeviceStatus status = lockDeviceStatus(deviceId);
        Optional<AlarmEpisode> existing = episodeRepository.findOpenDeviceSilenceForUpdate(deviceId);
        if (existing.isPresent()) {
            AlertSeverity previousSeverity = existing.get().getCurrentSeverity();
            existing.get().transitionSeverity(severity);
            return new OpenResult(existing.get(),
                    openOutcome(previousSeverity, severity));
        }
        AlarmEpisode created = episodeRepository.save(AlarmEpisode.openDeviceSilence(
                status.getDevice(), severity, clock.instant(), snapshot));
        publishEpisode(AlarmEpisodeSsePayload.ChangeType.OPEN, created);
        return new OpenResult(created, OpenOutcome.CREATED);
    }

    @Transactional
    public Optional<AlarmEpisode> resolveDeviceSilence(Long deviceId,
                                                       AlarmResolutionReason reason) {
        lockDeviceStatus(deviceId);
        Optional<AlarmEpisode> episode = episodeRepository.findOpenDeviceSilenceForUpdate(deviceId);
        episode.ifPresent(value -> resolveAndPublish(value, clock.instant(), reason));
        return episode;
    }

    @Transactional
    public AlarmEpisode openZoneSilence(Long zoneId, AlertSeverity severity,
                                        Map<String, Object> snapshot) {
        return openZoneSilenceResult(zoneId, severity, snapshot).episode();
    }

    @Transactional
    public OpenResult openZoneSilenceResult(Long zoneId, AlertSeverity severity,
                                            Map<String, Object> snapshot) {
        Zone zone = lockZone(zoneId);
        Optional<AlarmEpisode> existing = episodeRepository.findOpenZoneSilenceForUpdate(zoneId);
        if (existing.isPresent()) {
            AlertSeverity previousSeverity = existing.get().getCurrentSeverity();
            existing.get().transitionSeverity(severity);
            return new OpenResult(existing.get(),
                    openOutcome(previousSeverity, severity));
        }
        AlarmEpisode created = episodeRepository.save(
                AlarmEpisode.openZoneSilence(zone, severity, clock.instant(), snapshot));
        publishEpisode(AlarmEpisodeSsePayload.ChangeType.OPEN, created);
        return new OpenResult(created, OpenOutcome.CREATED);
    }

    @Transactional
    public Optional<AlarmEpisode> resolveZoneSilence(Long zoneId,
                                                     AlarmResolutionReason reason) {
        lockZone(zoneId);
        Optional<AlarmEpisode> episode = episodeRepository.findOpenZoneSilenceForUpdate(zoneId);
        episode.ifPresent(value -> resolveAndPublish(value, clock.instant(), reason));
        return episode;
    }

    /**
     * 한 zone의 freshness 전이를 직렬화한다.
     *
     * zone 행을 먼저 잠그고 device_status를 ID 오름차순으로 잠가, 여러 scheduler 인스턴스가
     * 같은 snapshot을 평가해도 동일 episode의 알림을 중복 생성하지 않게 한다.
     */
    @Transactional
    public List<Alert> reconcileFreshnessZone(Long zoneId,
                                              List<FreshnessDeviceState> deviceStates,
                                              Map<String, Object> zoneSnapshot) {
        if (deviceStates == null || deviceStates.isEmpty()) {
            return List.of();
        }
        lockZone(zoneId);
        List<FreshnessDeviceState> orderedStates = deviceStates.stream()
                .sorted(Comparator.comparing(FreshnessDeviceState::deviceId))
                .toList();
        List<Long> deviceIds = orderedStates.stream()
                .map(FreshnessDeviceState::deviceId)
                .distinct()
                .toList();
        if (deviceIds.size() != orderedStates.size()) {
            throw new IllegalArgumentException("같은 deviceId의 freshness 상태가 중복되었습니다");
        }

        List<DeviceStatus> lockedStatuses =
                deviceStatusRepository.findAllByIdForUpdateOrderById(deviceIds);
        if (lockedStatuses.size() != deviceIds.size()) {
            throw new IllegalStateException("zone freshness 대상 device_status 일부가 없습니다");
        }
        Map<Long, DeviceStatus> statusesById = lockedStatuses.stream()
                .collect(Collectors.toMap(DeviceStatus::getDeviceId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        boolean snapshotChanged = orderedStates.stream().anyMatch(state -> {
            DeviceStatus locked = statusesById.get(state.deviceId());
            return locked == null
                    || !Objects.equals(locked.getDevice().getZone().getId(), zoneId)
                    || !Objects.equals(locked.getDevice().getExpectedIntervalSeconds(),
                    state.expectedIntervalSeconds())
                    || !Objects.equals(locked.getLastSeenAt(), state.lastSeenAt());
        });
        if (snapshotChanged) {
            // ingest/config 변경과 읽기 snapshot이 경합했다. 오래된 판정으로 episode를 열지 않고
            // 다음 scheduler tick에서 최신 상태를 다시 평가한다.
            return List.of();
        }

        List<FreshnessDeviceState> eligible = orderedStates.stream()
                .filter(state -> state.state() == FreshnessPolicy.State.ONLINE
                        || state.state() == FreshnessPolicy.State.STALE)
                .toList();
        List<FreshnessDeviceState> stale = eligible.stream()
                .filter(state -> state.state() == FreshnessPolicy.State.STALE)
                .toList();
        boolean zoneSilence = eligible.size() >= ZONE_COHORT_MIN
                && stale.size() == eligible.size();
        Instant now = clock.instant();
        List<Alert> notifications = new ArrayList<>();

        Optional<AlarmEpisode> openZone = episodeRepository.findOpenZoneSilenceForUpdate(zoneId);
        if (openZone.isPresent() && policyChanged(openZone.get(), zoneSnapshot)) {
            resolveAndPublish(openZone.get(), now, allMonitoringDisabled(orderedStates)
                    ? AlarmResolutionReason.MONITORING_DISABLED
                    : AlarmResolutionReason.CONFIG_CHANGED);
            openZone = Optional.empty();
        }
        Map<Long, Optional<AlarmEpisode>> openDevices = new LinkedHashMap<>();
        for (FreshnessDeviceState state : orderedStates) {
            Optional<AlarmEpisode> openDevice =
                    episodeRepository.findOpenDeviceSilenceForUpdate(state.deviceId());
            if (openDevice.isPresent() && policyChanged(openDevice.get(), state.snapshot())) {
                resolveAndPublish(openDevice.get(), now,
                        state.state() == FreshnessPolicy.State.NOT_MONITORED
                                ? AlarmResolutionReason.MONITORING_DISABLED
                                : AlarmResolutionReason.CONFIG_CHANGED);
                openDevice = Optional.empty();
            }
            openDevices.put(state.deviceId(), openDevice);
        }

        if (zoneSilence) {
            for (Optional<AlarmEpisode> openDevice : openDevices.values()) {
                openDevice.ifPresent(episode ->
                        resolveAndPublish(episode, now, AlarmResolutionReason.RECLASSIFIED));
            }
            DeviceStatus representative = statusesById.get(stale.get(0).deviceId());
            AlarmEpisode zoneEpisode = openZone.orElseGet(() -> {
                AlarmEpisode created = episodeRepository.save(AlarmEpisode.openZoneSilence(
                        representative.getDevice().getZone(), AlertSeverity.CRITICAL,
                        now, zoneSnapshot));
                return created;
            });
            notifications.addAll(notifyIfDue(
                    zoneEpisode, representative, zoneMessage(representative, stale, now),
                    AlertSeverity.CRITICAL, openZone.isEmpty(), now));
            if (openZone.isEmpty()) {
                publishEpisode(AlarmEpisodeSsePayload.ChangeType.OPEN, zoneEpisode);
            }
        } else {
            openZone.ifPresent(episode -> resolveAndPublish(
                    episode, now, zoneResolutionReason(orderedStates, stale)));
            Set<Long> staleIds = stale.stream().map(FreshnessDeviceState::deviceId)
                    .collect(Collectors.toSet());
            for (FreshnessDeviceState state : orderedStates) {
                Optional<AlarmEpisode> openDevice = openDevices.get(state.deviceId());
                if (!staleIds.contains(state.deviceId())) {
                    openDevice.ifPresent(episode ->
                            resolveAndPublish(episode, now, resolutionReason(state.state())));
                    continue;
                }
                DeviceStatus status = statusesById.get(state.deviceId());
                AlarmEpisode deviceEpisode = openDevice.orElseGet(() -> {
                    AlarmEpisode created = episodeRepository.save(AlarmEpisode.openDeviceSilence(
                            status.getDevice(), AlertSeverity.CRITICAL, now, state.snapshot()));
                    return created;
                });
                notifications.addAll(notifyIfDue(
                        deviceEpisode, status, deviceMessage(status, state, now),
                        AlertSeverity.CRITICAL, openDevice.isEmpty(), now));
                if (openDevice.isEmpty()) {
                    publishEpisode(AlarmEpisodeSsePayload.ChangeType.OPEN, deviceEpisode);
                }
            }
        }
        return List.copyOf(notifications);
    }

    @Transactional
    public AlarmAcknowledgement acknowledge(Long episodeId, Long userId) {
        AlarmEpisode episode = episodeRepository.findByIdForUpdate(episodeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 episodeId입니다: " + episodeId));
        User actor = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 userId입니다: " + userId));
        return acknowledgementRepository.save(new AlarmAcknowledgement(
                episode, actor, episode.getCurrentSeverity(), clock.instant()));
    }

    private void resolveAndPublish(
            AlarmEpisode episode, Instant at, AlarmResolutionReason reason) {
        episode.resolve(at, reason);
        publishEpisode(AlarmEpisodeSsePayload.ChangeType.RESOLVED, episode);
    }

    private void publishEpisode(
            AlarmEpisodeSsePayload.ChangeType changeType, AlarmEpisode episode) {
        Long routeDeviceId = AlarmEpisodeService.routeDeviceId(episode);
        Long routeZoneId = AlarmEpisodeService.routeZoneId(episode);
        if (routeDeviceId == null && routeZoneId == null) {
            return;
        }
        eventPublisher.publishEvent(new SseBroadcastEvent(
                "alarm-episode",
                routeDeviceId,
                routeZoneId,
                new AlarmEpisodeSsePayload(
                        changeType, AlarmEpisodeResponse.from(episode, null))));
    }

    private ChannelStatus lockChannelStatus(Long channelId) {
        return channelStatusRepository.findByIdForUpdate(channelId)
                .orElseThrow(() -> new IllegalStateException(
                        "channel_status가 없습니다. migration/create 경계를 확인하세요: " + channelId));
    }

    private DeviceStatus lockDeviceStatus(Long deviceId) {
        return deviceStatusRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new IllegalStateException(
                        "device_status가 없습니다. migration/create 경계를 확인하세요: " + deviceId));
    }

    private Zone lockZone(Long zoneId) {
        return zoneRepository.findByIdForUpdate(zoneId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 zoneId입니다: " + zoneId));
    }

    private List<Alert> notifyIfDue(AlarmEpisode episode, DeviceStatus representative,
                                    String message, AlertSeverity severity,
                                    boolean newlyOpened, Instant now) {
        AlertSeverity previousSeverity = episode.getCurrentSeverity();
        episode.transitionSeverity(severity);

        AlertNotificationReason reason = notificationReason(
                episode, previousSeverity, severity, newlyOpened, now);
        if (reason == null) {
            return List.of();
        }

        Alert alert = alertRepository.save(notificationFactory.create(
                episode, representative.getDevice(), null, null, null, null,
                message, severity, reason));
        episode.markNotified(now);
        eventPublisher.publishEvent(new SseBroadcastEvent(
                "alert", representative.getDeviceId(), AlertResponse.from(alert)));
        return List.of(alert);
    }

    private AlertNotificationReason notificationReason(AlarmEpisode episode,
                                                       AlertSeverity previousSeverity,
                                                       AlertSeverity severity,
                                                       boolean newlyOpened,
                                                       Instant now) {
        if (newlyOpened || episode.getLastNotifiedAt() == null) {
            return AlertNotificationReason.INITIAL;
        }
        if (severityRank(severity) > severityRank(previousSeverity)) {
            return AlertNotificationReason.SEVERITY_ESCALATION;
        }
        if (now.isBefore(episode.getLastNotifiedAt().plus(REMINDER_INTERVAL))) {
            return null;
        }
        boolean acknowledgedAtCurrentSeverity = acknowledgementRepository
                .findFirstByEpisodeIdOrderByCreatedAtDesc(episode.getId())
                .map(ack -> !ack.getCreatedAt().isBefore(episode.getLastNotifiedAt())
                        && severityRank(ack.getAckSeverity()) >= severityRank(severity))
                .orElse(false);
        return acknowledgedAtCurrentSeverity ? null : AlertNotificationReason.REMINDER;
    }

    private static AlarmResolutionReason zoneResolutionReason(
            List<FreshnessDeviceState> states, List<FreshnessDeviceState> stale) {
        if (!stale.isEmpty()) {
            return AlarmResolutionReason.RECLASSIFIED;
        }
        if (states.stream().anyMatch(state ->
                state.state() == FreshnessPolicy.State.PLANNED_OFFLINE)) {
            return AlarmResolutionReason.PLANNED_OFFLINE;
        }
        if (states.stream().anyMatch(state ->
                state.state() == FreshnessPolicy.State.RESUME_GRACE)) {
            return AlarmResolutionReason.RESUME_GRACE;
        }
        if (states.stream().allMatch(state ->
                state.state() == FreshnessPolicy.State.NOT_MONITORED
                        || state.state() == FreshnessPolicy.State.NEVER_SEEN)) {
            return AlarmResolutionReason.MONITORING_DISABLED;
        }
        return AlarmResolutionReason.RECOVERED;
    }

    private static AlarmResolutionReason resolutionReason(FreshnessPolicy.State state) {
        return switch (state) {
            case PLANNED_OFFLINE -> AlarmResolutionReason.PLANNED_OFFLINE;
            case RESUME_GRACE -> AlarmResolutionReason.RESUME_GRACE;
            case NOT_MONITORED, NEVER_SEEN -> AlarmResolutionReason.MONITORING_DISABLED;
            case ONLINE -> AlarmResolutionReason.RECOVERED;
            case STALE -> throw new IllegalArgumentException("STALE은 resolve 사유가 될 수 없습니다");
        };
    }

    private static String deviceMessage(DeviceStatus status, FreshnessDeviceState state,
                                        Instant now) {
        Object expected = state.snapshot().get("expectedIntervalSeconds");
        long elapsed = status.getLastSeenAt() == null
                ? 0L : Math.max(0L, Duration.between(status.getLastSeenAt(), now).getSeconds());
        return "데이터 수신 끊김 - %s (기대주기 %ss, 경과 %ds)"
                .formatted(status.getDevice().getName(), expected, elapsed);
    }

    private static String zoneMessage(DeviceStatus representative,
                                      List<FreshnessDeviceState> stale, Instant now) {
        long elapsed = representative.getLastSeenAt() == null
                ? 0L : Math.max(0L,
                Duration.between(representative.getLastSeenAt(), now).getSeconds());
        return "구역 전체 수신 끊김 - %s (%d대 동시 침묵, 대표 경과 %ds)"
                .formatted(representative.getDevice().getZone().getName(), stale.size(), elapsed);
    }

    private static int severityRank(AlertSeverity severity) {
        return switch (severity) {
            case INFO -> 0;
            case WARNING -> 1;
            case CRITICAL -> 2;
        };
    }

    private static OpenOutcome openOutcome(AlertSeverity previous, AlertSeverity current) {
        return severityRank(current) > severityRank(previous)
                ? OpenOutcome.SEVERITY_ESCALATED : OpenOutcome.EXISTING;
    }

    private static boolean policyChanged(AlarmEpisode episode,
                                         Map<String, Object> currentSnapshot) {
        Object previous = episode.getSnapshot().get(
                episode.getAlarmType() == dev.bugi.sensor.alert.entity.AlarmType.ZONE_SILENCE
                        ? "policyFingerprint" : "expectedIntervalSeconds");
        Object current = currentSnapshot.get(
                episode.getAlarmType() == dev.bugi.sensor.alert.entity.AlarmType.ZONE_SILENCE
                        ? "policyFingerprint" : "expectedIntervalSeconds");
        return !Objects.equals(previous, current);
    }

    private static boolean allMonitoringDisabled(List<FreshnessDeviceState> states) {
        return states.stream().allMatch(state ->
                state.state() == FreshnessPolicy.State.NOT_MONITORED);
    }

    public record OpenResult(AlarmEpisode episode, OpenOutcome outcome) {
        public OpenResult {
            Objects.requireNonNull(episode, "episode");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public enum OpenOutcome {
        CREATED,
        EXISTING,
        SEVERITY_ESCALATED
    }
}
