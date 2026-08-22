package dev.bugi.sensor.admin.service;

import dev.bugi.sensor.admin.dto.ApproveRequest;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.entity.Zone;
import dev.bugi.sensor.factory.entity.ZoneUser;
import dev.bugi.sensor.factory.repository.FactoryRepository;
import dev.bugi.sensor.factory.repository.ZoneRepository;
import dev.bugi.sensor.factory.repository.ZoneUserRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.user.entity.UserStatus;
import dev.bugi.sensor.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock UserRepository userRepository;
    @Mock FactoryRepository factoryRepository;
    @Mock ZoneRepository zoneRepository;
    @Mock ZoneUserRepository zoneUserRepository;
    @Mock AccessControlService accessControlService;
    @InjectMocks AdminService adminService;

    private User caller(Role role, Long factoryId) {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(role);
        if (factoryId != null) {
            Factory userFactory = factory(factoryId);
            when(user.getFactory()).thenReturn(userFactory);
        }
        return user;
    }

    private User pendingTarget(Long id, Long factoryId) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.getStatus()).thenReturn(UserStatus.PENDING);
        if (factoryId != null) {
            Factory userFactory = factory(factoryId);
            lenient().when(user.getFactory()).thenReturn(userFactory);
        }
        return user;
    }

    private Factory factory(Long id) {
        Factory factory = mock(Factory.class);
        lenient().when(factory.getId()).thenReturn(id);
        return factory;
    }

    private Zone zoneInFactory(Long zoneId, Factory factory) {
        Zone zone = mock(Zone.class);
        lenient().when(zone.getId()).thenReturn(zoneId);
        lenient().when(zone.getFactory()).thenReturn(factory);
        return zone;
    }

    private void givenCallerAndTarget(String employeeId, User caller, Long targetId, User target) {
        when(userRepository.findByEmployeeId(employeeId)).thenReturn(Optional.of(caller));
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
    }

    @Test
    void factory_admin의_대기_목록은_자기_공장으로_조회한다() {
        User manager = caller(Role.FACTORY_ADMIN, 1L);
        when(userRepository.findByEmployeeId("MGR")).thenReturn(Optional.of(manager));
        when(userRepository.findAllByFactory_IdAndStatus(1L, UserStatus.PENDING)).thenReturn(List.of());

        adminService.getPendingUsers("MGR");

        verify(userRepository).findAllByFactory_IdAndStatus(1L, UserStatus.PENDING);
        verify(userRepository, never()).findAllByStatus(any());
    }

    @Test
    void system_viewer의_사용자_목록은_전체_범위로_조회한다() {
        User viewer = caller(Role.SYSTEM_VIEWER, null);
        when(userRepository.findByEmployeeId("DEMO")).thenReturn(Optional.of(viewer));
        when(userRepository.findAll()).thenReturn(List.of());

        adminService.getAllUsers("DEMO");

        verify(userRepository).findAll();
        verify(userRepository, never()).findAllByFactory_Id(any());
    }

    @Test
    void system_admin은_요청한_공장으로_기존_소속을_교정하고_구역을_배정한다() {
        User caller = caller(Role.SYSTEM_ADMIN, null);
        User target = pendingTarget(10L, 1L);
        Factory requestedFactory = factory(2L);
        Zone zone = zoneInFactory(5L, requestedFactory);
        givenCallerAndTarget("ADMIN", caller, 10L, target);
        when(factoryRepository.findById(2L)).thenReturn(Optional.of(requestedFactory));
        when(zoneRepository.findById(5L)).thenReturn(Optional.of(zone));

        adminService.approveUser(10L,
                new ApproveRequest(Role.MEMBER, 2L, List.of(5L)), "ADMIN");

        verify(accessControlService).assertCanManageZone(caller, zone);
        verify(target).approve(Role.MEMBER);
        verify(target).assignFactory(requestedFactory);
        verify(zoneUserRepository).deleteAllByUserId(10L);
        verify(zoneUserRepository).save(any(ZoneUser.class));
    }

    @Test
    void zone이_비어도_요청한_공장을_반드시_배정한다() {
        User caller = caller(Role.SYSTEM_ADMIN, null);
        User target = pendingTarget(10L, 1L);
        Factory requestedFactory = factory(2L);
        givenCallerAndTarget("ADMIN", caller, 10L, target);
        when(factoryRepository.findById(2L)).thenReturn(Optional.of(requestedFactory));

        adminService.approveUser(10L,
                new ApproveRequest(Role.VIEWER, 2L, List.of()), "ADMIN");

        verify(target).approve(Role.VIEWER);
        verify(target).assignFactory(requestedFactory);
        verify(zoneUserRepository).deleteAllByUserId(10L);
        verifyNoInteractions(zoneRepository, accessControlService);
    }

    @Test
    void system_admin도_선택한_공장과_다른_공장_구역은_배정할_수_없고_target은_변경되지_않는다() {
        User caller = caller(Role.SYSTEM_ADMIN, null);
        User target = pendingTarget(10L, 1L);
        Factory requestedFactory = factory(2L);
        Zone otherFactoryZone = zoneInFactory(9L, factory(3L));
        givenCallerAndTarget("ADMIN", caller, 10L, target);
        when(factoryRepository.findById(2L)).thenReturn(Optional.of(requestedFactory));
        when(zoneRepository.findById(9L)).thenReturn(Optional.of(otherFactoryZone));

        assertThrows(IllegalArgumentException.class, () -> adminService.approveUser(
                10L, new ApproveRequest(Role.MEMBER, 2L, List.of(9L)), "ADMIN"));

        verify(accessControlService).assertCanManageZone(caller, otherFactoryZone);
        verify(target, never()).approve(any());
        verify(target, never()).assignFactory(any());
        verifyNoInteractions(zoneUserRepository);
    }

    @Test
    void factory_admin은_자기_공장_사용자를_자기_공장_구역으로_승인한다() {
        User caller = caller(Role.FACTORY_ADMIN, 1L);
        User target = pendingTarget(10L, 1L);
        Factory requestedFactory = factory(1L);
        Zone zone = zoneInFactory(5L, requestedFactory);
        givenCallerAndTarget("MGR", caller, 10L, target);
        when(factoryRepository.findById(1L)).thenReturn(Optional.of(requestedFactory));
        when(zoneRepository.findById(5L)).thenReturn(Optional.of(zone));

        adminService.approveUser(10L,
                new ApproveRequest(Role.MEMBER, 1L, List.of(5L)), "MGR");

        verify(accessControlService).assertCanManageZone(caller, zone);
        verify(target).approve(Role.MEMBER);
        verify(target).assignFactory(requestedFactory);
        verify(zoneUserRepository).deleteAllByUserId(10L);
        verify(zoneUserRepository).save(any(ZoneUser.class));
    }

    @Test
    void factory_admin은_타_공장을_요청할_수_없고_target은_변경되지_않는다() {
        User caller = caller(Role.FACTORY_ADMIN, 1L);
        User target = pendingTarget(10L, 1L);
        Factory otherFactory = factory(2L);
        givenCallerAndTarget("MGR", caller, 10L, target);
        when(factoryRepository.findById(2L)).thenReturn(Optional.of(otherFactory));

        assertThrows(AccessDeniedException.class, () -> adminService.approveUser(
                10L, new ApproveRequest(Role.VIEWER, 2L, List.of()), "MGR"));

        verify(target, never()).approve(any());
        verify(target, never()).assignFactory(any());
        verifyNoInteractions(zoneRepository, zoneUserRepository, accessControlService);
    }

    @Test
    void factory_admin은_타_공장_사용자를_관리할_수_없다() {
        User caller = caller(Role.FACTORY_ADMIN, 1L);
        User target = pendingTarget(10L, 2L);
        givenCallerAndTarget("MGR", caller, 10L, target);

        assertThrows(AccessDeniedException.class, () -> adminService.approveUser(
                10L, new ApproveRequest(Role.VIEWER, 1L, List.of()), "MGR"));

        verifyNoInteractions(factoryRepository, zoneRepository, zoneUserRepository, accessControlService);
        verify(target, never()).approve(any());
        verify(target, never()).assignFactory(any());
    }

    @Test
    void factory_admin은_타_공장_구역을_배정할_수_없고_target은_변경되지_않는다() {
        User caller = caller(Role.FACTORY_ADMIN, 1L);
        User target = pendingTarget(10L, 1L);
        Factory requestedFactory = factory(1L);
        Zone otherFactoryZone = zoneInFactory(9L, factory(2L));
        givenCallerAndTarget("MGR", caller, 10L, target);
        when(factoryRepository.findById(1L)).thenReturn(Optional.of(requestedFactory));
        when(zoneRepository.findById(9L)).thenReturn(Optional.of(otherFactoryZone));
        doThrow(new AccessDeniedException("본인 공장의 구역만 관리할 수 있어요"))
                .when(accessControlService).assertCanManageZone(caller, otherFactoryZone);

        assertThrows(AccessDeniedException.class, () -> adminService.approveUser(
                10L, new ApproveRequest(Role.MEMBER, 1L, List.of(9L)), "MGR"));

        verify(target, never()).approve(any());
        verify(target, never()).assignFactory(any());
        verifyNoInteractions(zoneUserRepository);
    }

    @Test
    void factory_admin은_factory_admin_역할을_부여할_수_없다() {
        User caller = caller(Role.FACTORY_ADMIN, 1L);
        User target = pendingTarget(10L, 1L);
        givenCallerAndTarget("MGR", caller, 10L, target);

        assertThrows(AccessDeniedException.class, () -> adminService.approveUser(
                10L, new ApproveRequest(Role.FACTORY_ADMIN, 1L, List.of()), "MGR"));

        verifyNoInteractions(factoryRepository, zoneRepository, zoneUserRepository, accessControlService);
        verify(target, never()).approve(any());
        verify(target, never()).assignFactory(any());
    }
}
