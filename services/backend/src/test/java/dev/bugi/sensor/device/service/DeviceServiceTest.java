package dev.bugi.sensor.device.service;

import dev.bugi.sensor.device.dto.DeviceRegisterRequest;
import dev.bugi.sensor.device.dto.DeviceUpdateRequest;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.repository.ZoneRepository;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.user.entity.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock DeviceRepository deviceRepository;
    @Mock DeviceStatusRepository deviceStatusRepository;
    @Mock ZoneRepository zoneRepository;
    @Mock AccessControlService accessControlService;

    @InjectMocks
    DeviceService deviceService;

    private User mockUser() {
        return User.builder().employeeId("DEV001").name("장치담당자").password("encoded_password")
                .role(Role.MEMBER).status(UserStatus.ACTIVE).build();
    }

    private User viewerUser() {
        return User.builder().employeeId("VWR001").name("열람자").password("encoded_password")
                .role(Role.VIEWER).status(UserStatus.ACTIVE).build();
    }

    private Zone mockZone() {
        return Zone.builder().factory(Factory.builder().name("테스트공장").build()).name("1구역").build();
    }

    private Device mockDevice(Zone zone) {
        return Device.builder().zone(zone).code("CMAPSS-U1").name("엔진 유닛1")
                .location("C-MAPSS unit1").expectedIntervalSeconds(10).build();
    }

    @Test
    void register_success() {
        User user = mockUser();
        Zone zone = mockZone();
        DeviceRegisterRequest request = new DeviceRegisterRequest("CNC-EXP01", "CNC 1호기", "CNC exp01", 10, 1L);

        when(accessControlService.getUser("DEV001")).thenReturn(user);
        when(zoneRepository.findById(1L)).thenReturn(Optional.of(zone));
        doNothing().when(accessControlService).assertCanManageZone(user, zone);

        deviceService.register(request, "DEV001");

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(captor.capture());
        Device saved = captor.getValue();
        assertThat(saved.getCode()).isEqualTo("CNC-EXP01");
        assertThat(saved.getName()).isEqualTo("CNC 1호기");
        assertThat(saved.getLocation()).isEqualTo("CNC exp01");
        assertThat(saved.getExpectedIntervalSeconds()).isEqualTo(10);
        assertThat(saved.getZone()).isSameAs(zone);
    }

    @Test
    void register_fail_zone_not_found() {
        User user = mockUser();
        DeviceRegisterRequest request = new DeviceRegisterRequest("CNC-EXP01", "CNC 1호기", "L", 10, 99L);

        when(accessControlService.getUser("DEV001")).thenReturn(user);
        when(zoneRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> deviceService.register(request, "DEV001"));
    }

    @Test
    void update_success() {
        User user = mockUser();
        Device device = mockDevice(mockZone());
        DeviceUpdateRequest request = new DeviceUpdateRequest("엔진 유닛1-수정", "공장2층", 20);

        when(accessControlService.getUser("DEV001")).thenReturn(user);
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        doNothing().when(accessControlService).assertCanAccessDevice(user, device);

        deviceService.update(1L, request, "DEV001");

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(deviceRepository).save(captor.capture());
        Device saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("엔진 유닛1-수정");
        assertThat(saved.getLocation()).isEqualTo("공장2층");
        assertThat(saved.getExpectedIntervalSeconds()).isEqualTo(20);
        assertThat(saved.getCode()).isEqualTo("CMAPSS-U1"); // code 는 수정하지 않는다
    }

    @Test
    void update_fail_device_not_found() {
        User user = mockUser();
        when(accessControlService.getUser("DEV001")).thenReturn(user);
        when(deviceRepository.findById(1L)).thenReturn(Optional.empty());
        DeviceUpdateRequest request = new DeviceUpdateRequest("x", "y", 10);

        assertThrows(IllegalArgumentException.class, () -> deviceService.update(1L, request, "DEV001"));
    }

    @Test
    void delete_success() {
        User user = mockUser();
        Device device = mockDevice(mockZone());

        when(accessControlService.getUser("DEV001")).thenReturn(user);
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(device));
        doNothing().when(accessControlService).assertCanAccessDevice(user, device);

        deviceService.delete(1L, "DEV001");

        verify(deviceRepository, times(1)).delete(device);
    }

    @Test
    void delete_fail_device_not_found() {
        User user = mockUser();
        when(accessControlService.getUser("DEV001")).thenReturn(user);
        when(deviceRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> deviceService.delete(1L, "DEV001"));
    }

    @Test
    void register_viewer_forbidden() {
        User viewer = viewerUser();
        DeviceRegisterRequest request = new DeviceRegisterRequest("CNC-EXP01", "CNC 1호기", "L", 10, 1L);

        when(accessControlService.getUser("VWR001")).thenReturn(viewer);
        doThrow(new AccessDeniedException("열람 전용 계정은 장치를 변경할 수 없어요"))
                .when(accessControlService).assertCanMutateDevice(viewer);

        assertThrows(AccessDeniedException.class, () -> deviceService.register(request, "VWR001"));
        verify(deviceRepository, never()).save(any(Device.class));
    }
}
