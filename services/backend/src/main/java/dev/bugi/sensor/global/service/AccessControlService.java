package dev.bugi.sensor.global.service;

import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.SensorChannelRepository;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.repository.ZoneRepository;
import dev.bugi.sensor.factory.repository.ZoneUserRepository;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccessControlService {

    private final DeviceRepository deviceRepository;
    private final ZoneRepository zoneRepository;
    private final ZoneUserRepository zoneUserRepository;
    private final UserRepository userRepository;
    private final SensorChannelRepository sensorChannelRepository;

    // 사원번호로 사용자 로드(여러 서비스가 공유하던 중복 조회를 여기로 통합).
    @Transactional(readOnly = true)
    public User getUser(String employeeId) {
        return userRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사원번호예요"));
    }

    // 채널 로드(여러 서비스가 공유하던 findById().orElseThrow 중복을 통합).
    @Transactional(readOnly = true)
    public SensorChannel getChannel(Long channelId) {
        return sensorChannelRepository.findById(channelId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 채널이에요 - channelId: " + channelId));
    }

    @Transactional(readOnly = true)
    public List<Device> getAccessibleDevices(User user) {
        if (user.getRole().hasGlobalReadScope()) {
            return deviceRepository.findAll();
        }
        if (user.getRole() == Role.FACTORY_ADMIN) {
            if (user.getFactory() == null) return List.of();
            return deviceRepository.findAllByZone_Factory_Id(user.getFactory().getId());
        }
        List<Long> zoneIds = getZoneIds(user);
        if (zoneIds.isEmpty()) return List.of();
        return deviceRepository.findAllByZoneIdIn(zoneIds);
    }

    @Transactional(readOnly = true)
    public List<Long> getAccessibleDeviceIds(User user) {
        // id만 필요하므로 전 경로에서 id 프로젝션으로 조회 (엔티티 전량로드 회피)
        if (user.getRole().hasGlobalReadScope()) {
            return deviceRepository.findAllIds();
        }
        if (user.getRole() == Role.FACTORY_ADMIN) {
            if (user.getFactory() == null) return List.of();
            return deviceRepository.findIdsByFactoryId(user.getFactory().getId());
        }
        List<Long> zoneIds = getZoneIds(user);
        if (zoneIds.isEmpty()) return List.of();
        return deviceRepository.findIdsByZoneIdIn(zoneIds);
    }

    @Transactional(readOnly = true)
    public List<Zone> getAccessibleZones(User user) {
        if (user.getRole().hasGlobalReadScope()) {
            return zoneRepository.findAll();
        }
        if (user.getRole() == Role.FACTORY_ADMIN) {
            if (user.getFactory() == null) return List.of();
            return zoneRepository.findAllByFactoryId(user.getFactory().getId());
        }
        return zoneUserRepository.findAllByUserId(user.getId())
                .stream().map(zoneUser -> zoneUser.getZone()).toList();
    }

    @Transactional(readOnly = true)
    public void assertCanAccessDevice(User user, Device device) {
        if (user.getRole().hasGlobalReadScope()) return;
        if (user.getRole() == Role.FACTORY_ADMIN) {
            if (user.getFactory() == null ||
                    device.getZone() == null ||
                    !device.getZone().getFactory().getId().equals(user.getFactory().getId())) {
                throw new AccessDeniedException("접근 권한이 없는 장치예요");
            }
            return;
        }
        List<Long> zoneIds = getZoneIds(user);
        if (device.getZone() == null || !zoneIds.contains(device.getZone().getId())) {
            throw new AccessDeniedException("접근 권한이 없는 장치예요");
        }
    }

    // 채널 접근은 소속 물리 Device 접근 권한에 위임한다(채널은 device 의 자식 설정).
    @Transactional(readOnly = true)
    public void assertCanAccessChannel(User user, SensorChannel channel) {
        assertCanAccessDevice(user, channel.getDevice());
    }

    public void assertCanMutateDevice(User user) {
        if (user.getRole().isReadOnly()) {
            throw new AccessDeniedException("열람 전용 계정은 장치를 변경할 수 없어요");
        }
    }

    @Transactional(readOnly = true)
    public void assertCanManageZone(User user, Zone zone) {
        if (user.getRole().isReadOnly()) {
            throw new AccessDeniedException("열람 전용 계정은 구역을 변경할 수 없어요");
        }
        if (user.getRole() == Role.SYSTEM_ADMIN) return;
        if (user.getRole() == Role.FACTORY_ADMIN) {
            if (user.getFactory() == null ||
                    !zone.getFactory().getId().equals(user.getFactory().getId())) {
                throw new AccessDeniedException("본인 공장의 구역만 관리할 수 있어요");
            }
            return;
        }
        // MEMBER — 자기 구역만
        boolean inZone = zoneUserRepository.existsByZoneIdAndUserId(zone.getId(), user.getId());
        if (!inZone) {
            throw new AccessDeniedException("소속된 구역만 관리할 수 있어요");
        }
    }

    private List<Long> getZoneIds(User user) {
        return zoneUserRepository.findAllByUserId(user.getId())
                .stream().map(gu -> gu.getZone().getId()).toList();
    }
}
