package dev.bugi.sensor.alert.service;

import dev.bugi.sensor.alert.dto.AlarmEpisodeResponse;
import dev.bugi.sensor.alert.dto.AlarmEpisodeSsePayload;
import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmEpisodeStatus;
import dev.bugi.sensor.alert.entity.AlarmScopeType;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.repository.AlarmAcknowledgementRepository;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sse.SseBroadcastEvent;
import dev.bugi.sensor.user.entity.User;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AlarmEpisodeService {

    private final AlarmEpisodeRepository episodeRepository;
    private final AlarmAcknowledgementRepository acknowledgementRepository;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<AlarmEpisodeResponse> getEpisodes(
            String employeeId,
            AlarmEpisodeStatus status,
            AlarmType type,
            AlarmScopeType scopeType,
            Long deviceId,
            Long channelId,
            Long zoneId,
            Pageable pageable) {
        AccessScope access = accessScope(employeeId);
        assertFiltersAccessible(access, deviceId, channelId, zoneId);
        if (access.deviceIds().isEmpty() && access.zoneIds().isEmpty()) {
            return Page.empty(pageable);
        }

        Specification<AlarmEpisode> specification = accessible(access)
                .and(equalsIfPresent("status", status))
                .and(equalsIfPresent("alarmType", type))
                .and(equalsIfPresent("scopeType", scopeType))
                .and(idEqualsIfPresent("device", deviceId))
                .and(idEqualsIfPresent("channel", channelId))
                .and(idEqualsIfPresent("zone", zoneId));
        return episodeRepository.findAll(specification, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AlarmEpisodeResponse getEpisode(String employeeId, Long episodeId) {
        AccessScope access = accessScope(employeeId);
        AlarmEpisode episode = findEpisode(episodeId);
        assertCanAccess(access, episode);
        return toResponse(episode);
    }

    @Transactional
    public AlarmEpisodeResponse acknowledge(String employeeId, Long episodeId) {
        User actor = accessControlService.getUser(employeeId);
        if (actor.getRole().isReadOnly()) {
            throw new AccessDeniedException("열람 전용 계정은 알람을 확인 처리할 수 없어요");
        }
        AccessScope access = new AccessScope(
                actor,
                accessControlService.getAccessibleDeviceIds(actor),
                accessControlService.getAccessibleZones(actor).stream().map(zone -> zone.getId()).toList());
        AlarmEpisode episode = episodeRepository.findByIdForUpdate(episodeId)
                .orElseThrow(() -> notFound(episodeId));
        assertCanAccess(access, episode);
        if (!episode.isOpen()) {
            throw new IllegalStateException("종료된 alarm episode는 확인 처리할 수 없어요");
        }

        AlarmAcknowledgement latest = acknowledgementRepository
                .findFirstByEpisodeIdOrderByCreatedAtDesc(episodeId)
                .orElse(null);
        boolean alreadyAcknowledgedCurrentNotification = latest != null
                && severityRank(latest.getAckSeverity())
                >= severityRank(episode.getCurrentSeverity())
                && (episode.getLastNotifiedAt() == null
                || !latest.getCreatedAt().isBefore(episode.getLastNotifiedAt()));
        boolean created = !alreadyAcknowledgedCurrentNotification;
        if (created) {
            latest = acknowledgementRepository.save(new AlarmAcknowledgement(
                    episode, actor, episode.getCurrentSeverity(), clock.instant()));
        }
        AlarmEpisodeResponse response = AlarmEpisodeResponse.from(episode, latest);
        Long routeDeviceId = routeDeviceId(episode);
        Long routeZoneId = routeZoneId(episode);
        if (created && (routeDeviceId != null || routeZoneId != null)) {
            eventPublisher.publishEvent(new SseBroadcastEvent(
                    "alarm-episode", routeDeviceId, routeZoneId,
                    new AlarmEpisodeSsePayload(AlarmEpisodeSsePayload.ChangeType.ACK, response)));
        }
        return response;
    }

    private AlarmEpisodeResponse toResponse(AlarmEpisode episode) {
        AlarmAcknowledgement latest = acknowledgementRepository
                .findFirstByEpisodeIdOrderByCreatedAtDesc(episode.getId())
                .orElse(null);
        return AlarmEpisodeResponse.from(episode, latest);
    }

    private AccessScope accessScope(String employeeId) {
        User user = accessControlService.getUser(employeeId);
        return new AccessScope(
                user,
                accessControlService.getAccessibleDeviceIds(user),
                accessControlService.getAccessibleZones(user).stream().map(zone -> zone.getId()).toList());
    }

    private void assertFiltersAccessible(
            AccessScope access, Long deviceId, Long channelId, Long zoneId) {
        if (deviceId != null && !access.deviceIds().contains(deviceId)) {
            throw new AccessDeniedException("접근 권한이 없는 장치예요");
        }
        if (channelId != null) {
            var channel = accessControlService.getChannel(channelId);
            accessControlService.assertCanAccessChannel(access.user(), channel);
        }
        if (zoneId != null && !access.zoneIds().contains(zoneId)) {
            throw new AccessDeniedException("접근 권한이 없는 구역이에요");
        }
    }

    private void assertCanAccess(AccessScope access, AlarmEpisode episode) {
        boolean allowed = switch (episode.getScopeType()) {
            case CHANNEL, DEVICE -> episode.getDevice() != null
                    && access.deviceIds().contains(episode.getDevice().getId());
            case ZONE -> episode.getZone() != null
                    && access.zoneIds().contains(episode.getZone().getId());
        };
        if (!allowed) {
            throw new AccessDeniedException("접근 권한이 없는 alarm episode예요");
        }
    }

    private AlarmEpisode findEpisode(Long episodeId) {
        return episodeRepository.findById(episodeId)
                .orElseThrow(() -> notFound(episodeId));
    }

    private static ResponseStatusException notFound(Long episodeId) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "존재하지 않는 alarm episode예요: " + episodeId);
    }

    private static Specification<AlarmEpisode> accessible(AccessScope access) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>(2);
            if (!access.deviceIds().isEmpty()) {
                predicates.add(root.get("device").get("id").in(access.deviceIds()));
            }
            if (!access.zoneIds().isEmpty()) {
                predicates.add(root.get("zone").get("id").in(access.zoneIds()));
            }
            return builder.or(predicates.toArray(Predicate[]::new));
        };
    }

    private static <T> Specification<AlarmEpisode> equalsIfPresent(String field, T value) {
        return value == null ? null
                : (root, query, builder) -> builder.equal(root.get(field), value);
    }

    private static Specification<AlarmEpisode> idEqualsIfPresent(String field, Long id) {
        return id == null ? null
                : (root, query, builder) -> builder.equal(root.get(field).get("id"), id);
    }

    private static int severityRank(AlertSeverity severity) {
        return switch (Objects.requireNonNull(severity, "severity")) {
            case INFO -> 0;
            case WARNING -> 1;
            case CRITICAL -> 2;
        };
    }

    public static Long routeDeviceId(AlarmEpisode episode) {
        return episode.getDevice() == null ? null : episode.getDevice().getId();
    }

    public static Long routeZoneId(AlarmEpisode episode) {
        return episode.getZone() == null ? null : episode.getZone().getId();
    }

    private record AccessScope(User user, List<Long> deviceIds, List<Long> zoneIds) {
    }
}
