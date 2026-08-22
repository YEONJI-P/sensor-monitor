package dev.bugi.sensor.admin.service;

import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.DateOverride;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.Interval;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.OverrideInterval;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.ReplaceRequest;
import dev.bugi.sensor.factory.calendar.entity.FactoryDateOverride;
import dev.bugi.sensor.factory.calendar.entity.FactoryOperatingCalendar;
import dev.bugi.sensor.factory.calendar.repository.FactoryDateOverrideIntervalRepository;
import dev.bugi.sensor.factory.calendar.repository.FactoryDateOverrideRepository;
import dev.bugi.sensor.factory.calendar.repository.FactoryOperatingCalendarRepository;
import dev.bugi.sensor.factory.calendar.repository.FactoryWeeklyIntervalRepository;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.repository.FactoryRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactoryCalendarAdminServiceTest {

    @Mock AccessControlService accessControlService;
    @Mock FactoryRepository factoryRepository;
    @Mock FactoryOperatingCalendarRepository calendarRepository;
    @Mock FactoryWeeklyIntervalRepository weeklyRepository;
    @Mock FactoryDateOverrideRepository overrideRepository;
    @Mock FactoryDateOverrideIntervalRepository overrideIntervalRepository;

    FactoryCalendarAdminService service;
    Factory factory;

    @BeforeEach
    void setUp() {
        service = new FactoryCalendarAdminService(accessControlService, factoryRepository, calendarRepository,
                weeklyRepository, overrideRepository, overrideIntervalRepository,
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
        factory = mock(Factory.class);
    }

    @Test
    void factory_admin은_타공장_URL을_403으로_거부한다() {
        User user = mock(User.class);
        Factory own = mock(Factory.class);
        when(own.getId()).thenReturn(1L);
        when(user.getRole()).thenReturn(Role.FACTORY_ADMIN);
        when(user.getFactory()).thenReturn(own);
        when(accessControlService.getUser("ADMIN")).thenReturn(user);
        Factory other = mock(Factory.class);
        when(factoryRepository.findById(2L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.get("ADMIN", 2L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(calendarRepository);
    }

    @Test
    void system_viewer는_모든_공장_캘린더_요약을_조회한다() {
        User viewer = mock(User.class);
        when(viewer.getRole()).thenReturn(Role.SYSTEM_VIEWER);
        when(accessControlService.getUser("DEMO")).thenReturn(viewer);
        Factory first = mock(Factory.class);
        Factory second = mock(Factory.class);
        when(first.getId()).thenReturn(1L);
        when(first.getName()).thenReturn("A 공장");
        when(second.getId()).thenReturn(2L);
        when(second.getName()).thenReturn("B 공장");
        when(factoryRepository.findAll()).thenReturn(List.of(second, first));

        var summaries = service.summaries("DEMO");

        assertThat(summaries).extracting(summary -> summary.factoryName())
                .containsExactly("A 공장", "B 공장");
    }

    @Test
    void system_viewer는_캘린더를_변경할_수_없다() {
        User viewer = mock(User.class);
        when(viewer.getRole()).thenReturn(Role.SYSTEM_VIEWER);
        when(accessControlService.getUser("DEMO")).thenReturn(viewer);

        assertThatThrownBy(() -> service.replace("DEMO", 1L, validRequest(0L)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(factoryRepository, calendarRepository, weeklyRepository,
                overrideRepository, overrideIntervalRepository);
    }

    @Test
    void stale_revision은_409예외이고_children을_변경하지_않는다() {
        systemAdmin();
        when(factoryRepository.findById(1L)).thenReturn(Optional.of(factory));
        FactoryOperatingCalendar calendar = mock(FactoryOperatingCalendar.class);
        when(calendar.getRevision()).thenReturn(3L);
        when(calendarRepository.findForUpdate(1L)).thenReturn(Optional.of(calendar));

        assertThatThrownBy(() -> service.replace("SYSTEM", 1L, validRequest(2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다른 관리자");

        verify(weeklyRepository, never()).deleteAllByFactoryId(1L);
        verify(overrideRepository, never()).deleteAllByFactoryId(1L);
    }

    @Test
    void 겹치거나_맞닿은_구간은_400예외이고_DB를_건드리지_않는다() {
        systemAdmin();
        when(factoryRepository.findById(1L)).thenReturn(Optional.of(factory));
        ReplaceRequest invalid = new ReplaceRequest("Asia/Seoul", 300, 0L, List.of(
                new Interval(DayOfWeek.MONDAY, "08:00", "12:00"),
                new Interval(DayOfWeek.MONDAY, "12:00", "18:00")), List.of());

        assertThatThrownBy(() -> service.replace("SYSTEM", 1L, invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("맞닿을");
        verify(calendarRepository, never()).findForUpdate(1L);
    }

    @Test
    void CLOSED는_구간을_가질수없고_OPEN은_구간이_필수다() {
        systemAdmin();
        when(factoryRepository.findById(1L)).thenReturn(Optional.of(factory));
        ReplaceRequest closedWithInterval = new ReplaceRequest("UTC", 0, 0L, List.of(), List.of(
                new DateOverride(LocalDate.of(2026, 7, 20), FactoryDateOverride.Kind.CLOSED,
                        List.of(new OverrideInterval("08:00", "09:00")))));
        ReplaceRequest openEmpty = new ReplaceRequest("UTC", 0, 0L, List.of(), List.of(
                new DateOverride(LocalDate.of(2026, 7, 21), FactoryDateOverride.Kind.OPEN, List.of())));

        assertThatThrownBy(() -> service.replace("SYSTEM", 1L, closedWithInterval))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("휴무일");
        assertThatThrownBy(() -> service.replace("SYSTEM", 1L, openEmpty))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("특근일");
    }

    @Test
    void 잘못된_timezone과_overnight는_거부한다() {
        systemAdmin();
        when(factoryRepository.findById(1L)).thenReturn(Optional.of(factory));
        ReplaceRequest badZone = new ReplaceRequest("Mars/Olympus", 300, 0L, List.of(), List.of());
        ReplaceRequest overnight = new ReplaceRequest("UTC", 300, 0L,
                List.of(new Interval(DayOfWeek.MONDAY, "22:00", "06:00")), List.of());

        assertThatThrownBy(() -> service.replace("SYSTEM", 1L, badZone))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timezone");
        assertThatThrownBy(() -> service.replace("SYSTEM", 1L, overnight))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("자정");
    }

    private ReplaceRequest validRequest(long revision) {
        return new ReplaceRequest("Asia/Seoul", 300, revision,
                List.of(new Interval(DayOfWeek.MONDAY, "08:00", "18:00")), List.of());
    }

    private void systemAdmin() {
        User user = mock(User.class);
        when(user.getRole()).thenReturn(Role.SYSTEM_ADMIN);
        when(accessControlService.getUser("SYSTEM")).thenReturn(user);
    }
}
