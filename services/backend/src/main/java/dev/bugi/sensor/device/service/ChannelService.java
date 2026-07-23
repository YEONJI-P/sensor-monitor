package dev.bugi.sensor.device.service;

import dev.bugi.sensor.alert.entity.AlarmResolutionReason;
import dev.bugi.sensor.alert.dto.AlarmEpisodeResponse;
import dev.bugi.sensor.alert.dto.AlarmEpisodeSsePayload;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.device.dto.ChannelCreateRequest;
import dev.bugi.sensor.device.dto.ChannelResponse;
import dev.bugi.sensor.device.dto.ChannelUpdateRequest;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.SensorChannelRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sse.SseBroadcastEvent;
import dev.bugi.sensor.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ChannelService {

    private final DeviceRepository deviceRepository;
    private final SensorChannelRepository sensorChannelRepository;
    private final ChannelStatusRepository channelStatusRepository;
    private final AlarmEpisodeRepository alarmEpisodeRepository;
    private final AccessControlService accessControlService;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 접근 가능한 채널 목록. deviceId 가 있으면 그 장치의 채널만(접근 검사 후), 없으면 접근 범위 전체.
     */
    @Transactional(readOnly = true)
    public List<ChannelResponse> getMyChannels(String employeeId, Long deviceId) {
        User user = accessControlService.getUser(employeeId);
        if (deviceId != null) {
            Device device = getDevice(deviceId);
            accessControlService.assertCanAccessDevice(user, device);
            return sensorChannelRepository.findByDeviceIdInWithDeviceAndZone(List.of(deviceId))
                    .stream().map(ChannelResponse::from).toList();
        }
        List<Long> deviceIds = accessControlService.getAccessibleDeviceIds(user);
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return sensorChannelRepository.findByDeviceIdInWithDeviceAndZone(deviceIds)
                .stream().map(ChannelResponse::from).toList();
    }

    @Transactional
    public ChannelResponse createChannel(Long deviceId, ChannelCreateRequest request, String employeeId) {
        User user = accessControlService.getUser(employeeId);
        accessControlService.assertCanMutateDevice(user);
        Device device = getDevice(deviceId);
        accessControlService.assertCanAccessDevice(user, device);
        validateThreshold(request.getThresholdValue(), request.getThresholdDirection());

        SensorChannel channel = SensorChannel.builder()
                .device(device)
                .code(request.getCode())
                .unit(request.getUnit())
                .quantityKind(request.getQuantityKind())
                .thresholdValue(request.getThresholdValue())
                .thresholdDirection(request.getThresholdDirection())
                .build();
        sensorChannelRepository.save(channel);
        channelStatusRepository.save(new ChannelStatus(channel));
        return ChannelResponse.from(channel);
    }

    @Transactional
    public ChannelResponse updateChannel(Long channelId, ChannelUpdateRequest request, String employeeId) {
        User user = accessControlService.getUser(employeeId);
        accessControlService.assertCanMutateDevice(user);
        SensorChannel channel = accessControlService.getChannel(channelId);
        accessControlService.assertCanAccessChannel(user, channel);
        validateThreshold(request.getThresholdValue(), request.getThresholdDirection());

        ChannelStatus status = channelStatusRepository.findByIdForUpdate(channelId)
                .orElseThrow(() -> new IllegalStateException(
                        "channel_status가 없습니다. migration/create 경계를 확인하세요: " + channelId));
        // ingest와 같은 coordination row를 얻은 뒤, 대기 전에 읽은 설정 스냅샷을 버린다.
        entityManager.refresh(channel);
        boolean thresholdChanged =
                !Objects.equals(channel.getThresholdValue(), request.getThresholdValue())
                        || !Objects.equals(channel.getThresholdDirection(), request.getThresholdDirection());
        if (thresholdChanged) {
            alarmEpisodeRepository.findOpenThresholdForUpdate(channelId)
                    .ifPresent(episode -> {
                        episode.resolve(clock.instant(), AlarmResolutionReason.CONFIG_CHANGED);
                        eventPublisher.publishEvent(new SseBroadcastEvent(
                                "alarm-episode", episode.getDevice().getId(),
                                new AlarmEpisodeSsePayload(
                                        AlarmEpisodeSsePayload.ChangeType.RESOLVED,
                                        AlarmEpisodeResponse.from(episode, null))));
                    });
            status.clearAlarm();
        }

        channel.update(request.getUnit(), request.getQuantityKind(),
                request.getThresholdValue(), request.getThresholdDirection());
        sensorChannelRepository.save(channel);
        return ChannelResponse.from(channel);
    }

    private Device getDevice(Long deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 장치예요 - deviceId: " + deviceId));
    }

    private void validateThreshold(Double thresholdValue, SensorChannel.ThresholdDirection direction) {
        if ((thresholdValue == null) != (direction == null)) {
            throw new IllegalArgumentException("임계값과 방향은 함께 입력하거나 함께 비워야 해요");
        }
        if (direction == SensorChannel.ThresholdDirection.ABS_ABOVE
                && thresholdValue <= 0) {
            throw new IllegalArgumentException("ABS_ABOVE 임계값은 0보다 커야 해요");
        }
    }
}
