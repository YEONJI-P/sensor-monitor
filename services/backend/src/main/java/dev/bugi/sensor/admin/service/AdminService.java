package dev.bugi.sensor.admin.service;

import dev.bugi.sensor.admin.dto.ApproveRequest;
import dev.bugi.sensor.admin.dto.UserResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final FactoryRepository factoryRepository;
    private final ZoneRepository zoneRepository;
    private final ZoneUserRepository zoneUserRepository;
    private final AccessControlService accessControlService;

    @Transactional(readOnly = true)
    public List<UserResponse> getPendingUsers(String employeeId) {
        User caller = getCaller(employeeId);
        List<User> users = caller.getRole().hasGlobalReadScope()
                ? userRepository.findAllByStatus(UserStatus.PENDING)
                : userRepository.findAllByFactory_IdAndStatus(callerFactoryId(caller), UserStatus.PENDING);
        return users.stream().map(UserResponse::new).toList();
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers(String employeeId) {
        User caller = getCaller(employeeId);
        List<User> users = caller.getRole().hasGlobalReadScope()
                ? userRepository.findAll()
                : userRepository.findAllByFactory_Id(callerFactoryId(caller));
        return users.stream().map(UserResponse::new).toList();
    }

    // 승인 = 상태 ACTIVE + 역할·공장 부여 + 구역 배정을 한 트랜잭션에서.
    @Transactional
    public void approveUser(Long id, ApproveRequest request, String employeeId) {
        User caller = getCaller(employeeId);
        User target = getUser(id);
        assertCanManage(caller, target);
        if (target.getStatus() != UserStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 사용자만 승인할 수 있어요");
        }
        assertCanGrantRole(caller, request.role());

        Factory factory = getFactory(request.factoryId());
        assertCanAssignFactory(caller, factory);

        // 공장과 구역 검증을 모두 끝낸 뒤에만 대상 상태·소속을 변경한다.
        List<Zone> zones = request.zoneIds().stream().map(this::getZone).toList();
        validateZonesInFactory(caller, factory, zones);

        target.approve(request.role());
        target.assignFactory(factory);
        // 승인 요청을 최종 구역 목록으로 취급해 기존 타 공장 배정이 남지 않게 한다.
        zoneUserRepository.deleteAllByUserId(target.getId());
        for (Zone zone : zones) {
            zoneUserRepository.save(ZoneUser.builder().zone(zone).user(target).build());
        }
    }

    @Transactional
    public void rejectUser(Long id, String employeeId) {
        User target = getUser(id);
        assertCanManage(getCaller(employeeId), target);
        if (target.getStatus() != UserStatus.PENDING) {
            throw new IllegalArgumentException("대기 중인 사용자만 반려할 수 있어요");
        }
        target.reject();
    }

    // 권한 상승 차단: FACTORY_ADMIN은 VIEWER/MEMBER까지만, SYSTEM_ADMIN은 FACTORY_ADMIN까지.
    // (SYSTEM_ADMIN 부여는 승인 경로로 불가 — seed 전용)
    private void assertCanGrantRole(User caller, Role role) {
        boolean allowed = isSystemAdmin(caller)
                ? (role == Role.VIEWER || role == Role.MEMBER || role == Role.FACTORY_ADMIN)
                : (role == Role.VIEWER || role == Role.MEMBER);
        if (!allowed) {
            throw new AccessDeniedException("부여할 수 없는 역할이에요");
        }
    }

    private void assertCanAssignFactory(User caller, Factory factory) {
        if (isSystemAdmin(caller)) {
            return;
        }
        if (!callerFactoryId(caller).equals(factory.getId())) {
            throw new AccessDeniedException("본인 공장으로만 사용자를 승인할 수 있어요");
        }
    }

    // 각 구역에 대한 관리 권한을 확인하고 요청한 공장 소속인지 검증한다.
    private void validateZonesInFactory(User caller, Factory factory, List<Zone> zones) {
        for (Zone zone : zones) {
            accessControlService.assertCanManageZone(caller, zone);
            if (!factory.getId().equals(zone.getFactory().getId())) {
                throw new IllegalArgumentException("선택한 공장 소속 구역만 배정할 수 있어요");
            }
        }
    }

    // FACTORY_ADMIN은 자기 공장 소속 사용자만 관리 가능. SYSTEM_ADMIN은 전체.
    private void assertCanManage(User caller, User target) {
        if (isSystemAdmin(caller)) {
            return;
        }
        Long callerFactory = callerFactoryId(caller);
        Long targetFactory = target.getFactory() != null ? target.getFactory().getId() : null;
        if (!callerFactory.equals(targetFactory)) {
            throw new AccessDeniedException("소속 공장의 사용자만 관리할 수 있어요");
        }
    }

    private boolean isSystemAdmin(User user) {
        return user.getRole() == Role.SYSTEM_ADMIN;
    }

    // FACTORY_ADMIN이 공장에 배정돼 있어야 스코핑이 성립한다.
    private Long callerFactoryId(User caller) {
        if (caller.getFactory() == null) {
            throw new AccessDeniedException("소속 공장이 없어 사용자 관리 권한이 없어요");
        }
        return caller.getFactory().getId();
    }

    private Zone getZone(Long id) {
        return zoneRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 구역이에요 - id " + id));
    }

    private Factory getFactory(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("배정할 공장은 필수예요");
        }
        if (id <= 0) {
            throw new IllegalArgumentException("공장 ID는 1 이상이어야 해요");
        }
        return factoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 공장이에요 - id " + id));
    }

    private User getCaller(String employeeId) {
        return userRepository.findByEmployeeId(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자예요"));
    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자예요"));
    }
}
