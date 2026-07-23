package dev.bugi.sensor.device.service;

import dev.bugi.sensor.device.dto.DeviceRegisterRequest;
import dev.bugi.sensor.device.dto.DeviceResponse;
import dev.bugi.sensor.device.dto.DeviceUpdateRequest;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.DeviceStatus;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.repository.ZoneRepository;
import dev.bugi.sensor.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceStatusRepository deviceStatusRepository;
    private final ZoneRepository zoneRepository;
    private final AccessControlService accessControlService;

    @Transactional
    public DeviceResponse register(DeviceRegisterRequest request, String employeeId) {
        User user = accessControlService.getUser(employeeId);
        accessControlService.assertCanMutateDevice(user);
        Zone zone = zoneRepository.findById(request.getZoneId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 구역이에요"));
        accessControlService.assertCanManageZone(user, zone);

        Device device = Device.builder()
                .zone(zone)
                .code(request.getCode())
                .name(request.getName())
                .location(request.getLocation())
                .expectedIntervalSeconds(request.getExpectedIntervalSeconds())
                .build();
        deviceRepository.save(device);
        deviceStatusRepository.save(new DeviceStatus(device));
        return DeviceResponse.from(device);
    }

    @Transactional
    public DeviceResponse update(Long deviceId, DeviceUpdateRequest request, String employeeId) {
        User user = accessControlService.getUser(employeeId);
        accessControlService.assertCanMutateDevice(user);
        Device device = getDevice(deviceId);
        accessControlService.assertCanAccessDevice(user, device);

        device.update(request.getName(), request.getLocation(), request.getExpectedIntervalSeconds());
        deviceRepository.save(device);
        return DeviceResponse.from(device);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getMyDevices(String employeeId) {
        User user = accessControlService.getUser(employeeId);
        return accessControlService.getAccessibleDevices(user).stream()
                .map(DeviceResponse::from)
                .toList();
    }

    @Transactional
    public void delete(Long deviceId, String employeeId) {
        User user = accessControlService.getUser(employeeId);
        accessControlService.assertCanMutateDevice(user);
        Device device = getDevice(deviceId);
        accessControlService.assertCanAccessDevice(user, device);
        deviceRepository.delete(device);
    }

    private Device getDevice(Long deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("장치 정보가 존재하지 않아요"));
    }
}
