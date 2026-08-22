package dev.bugi.sensor.admin.service;

import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.DateOverride;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.Detail;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.Interval;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.OverrideInterval;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.ReplaceRequest;
import dev.bugi.sensor.admin.dto.FactoryCalendarDtos.Summary;
import dev.bugi.sensor.factory.calendar.entity.FactoryDateOverride;
import dev.bugi.sensor.factory.calendar.entity.FactoryDateOverrideInterval;
import dev.bugi.sensor.factory.calendar.entity.FactoryOperatingCalendar;
import dev.bugi.sensor.factory.calendar.entity.FactoryWeeklyInterval;
import dev.bugi.sensor.factory.calendar.repository.FactoryDateOverrideIntervalRepository;
import dev.bugi.sensor.factory.calendar.repository.FactoryDateOverrideRepository;
import dev.bugi.sensor.factory.calendar.repository.FactoryOperatingCalendarRepository;
import dev.bugi.sensor.factory.calendar.repository.FactoryWeeklyIntervalRepository;
import dev.bugi.sensor.factory.entity.Factory;
import dev.bugi.sensor.factory.repository.FactoryRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class FactoryCalendarAdminService {

    public static final String DEFAULT_TIMEZONE = "Asia/Seoul";
    public static final int DEFAULT_RESUME_GRACE_SECONDS = 300;
    private static final int MAX_INTERVALS_PER_DAY = 12;
    private static final int MAX_OVERRIDES = 1000;
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{2}):(\\d{2})$");

    private final AccessControlService accessControlService;
    private final FactoryRepository factoryRepository;
    private final FactoryOperatingCalendarRepository calendarRepository;
    private final FactoryWeeklyIntervalRepository weeklyRepository;
    private final FactoryDateOverrideRepository overrideRepository;
    private final FactoryDateOverrideIntervalRepository overrideIntervalRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Summary> summaries(String employeeId) {
        User user = requireReader(employeeId);
        List<Factory> factories;
        if (user.getRole().hasGlobalReadScope()) {
            factories = factoryRepository.findAll().stream()
                    .sorted(Comparator.comparing(Factory::getName)).toList();
        } else {
            factories = user.getFactory() == null ? List.of() : List.of(user.getFactory());
        }
        Map<Long, FactoryOperatingCalendar> calendars = new HashMap<>();
        if (!factories.isEmpty()) {
            calendarRepository.findAllWithFactoryByFactoryIdIn(factories.stream().map(Factory::getId).toList())
                    .forEach(calendar -> calendars.put(calendar.getFactoryId(), calendar));
        }
        return factories.stream().map(factory -> toSummary(factory, calendars.get(factory.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public Detail get(String employeeId, Long factoryId) {
        User user = requireReader(employeeId);
        Factory factory = getFactory(factoryId);
        assertScope(user, factoryId);
        FactoryOperatingCalendar calendar = calendarRepository.findById(factoryId).orElse(null);
        return calendar == null ? defaultDetail(factory) : detail(calendar);
    }

    @Transactional
    public Detail replace(String employeeId, Long factoryId, ReplaceRequest request) {
        User user = requireAdmin(employeeId);
        Factory factory = getFactory(factoryId);
        assertScope(user, factoryId);
        ValidatedCalendar validated = validate(request);

        FactoryOperatingCalendar calendar = calendarRepository.findForUpdate(factoryId).orElse(null);
        if (calendar == null) {
            if (validated.revision() != 0) throw conflict();
            Instant now = clock.instant();
            calendar = calendarRepository.save(new FactoryOperatingCalendar(
                    factory, validated.timezone(), validated.resumeGraceSeconds(), now));
        } else if (calendar.getRevision() != validated.revision()) {
            throw conflict();
        }

        calendar.replaceSettings(validated.timezone(), validated.resumeGraceSeconds(), clock.instant());
        overrideIntervalRepository.deleteAllByFactoryId(factoryId);
        overrideRepository.deleteAllByFactoryId(factoryId);
        weeklyRepository.deleteAllByFactoryId(factoryId);
        overrideRepository.flush();
        weeklyRepository.flush();

        FactoryOperatingCalendar target = calendar;
        weeklyRepository.saveAll(validated.weekly().stream()
                .map(interval -> new FactoryWeeklyInterval(target, interval.day().getValue(),
                        interval.startMinute(), interval.endMinute())).toList());
        for (ValidatedOverride source : validated.overrides()) {
            FactoryDateOverride saved = overrideRepository.save(
                    new FactoryDateOverride(calendar, source.date(), source.kind()));
            overrideIntervalRepository.saveAll(source.intervals().stream()
                    .map(interval -> new FactoryDateOverrideInterval(saved,
                            interval.startMinute(), interval.endMinute())).toList());
        }
        calendarRepository.flush();
        return detail(calendar);
    }

    private Detail detail(FactoryOperatingCalendar calendar) {
        List<Interval> weekly = weeklyRepository.findAllByFactoryId(calendar.getFactoryId()).stream()
                .map(i -> new Interval(DayOfWeek.of(i.getIsoDay()), formatMinute(i.getStartMinute()),
                        formatMinute(i.getEndMinute())))
                .toList();
        List<FactoryDateOverride> overrides = overrideRepository.findAllByFactoryId(calendar.getFactoryId());
        Map<Long, List<FactoryDateOverrideInterval>> intervals = new HashMap<>();
        if (!overrides.isEmpty()) {
            overrideIntervalRepository.findAllByOverrideIdIn(overrides.stream().map(FactoryDateOverride::getId).toList())
                    .forEach(i -> intervals.computeIfAbsent(i.getOverride().getId(), ignored -> new ArrayList<>()).add(i));
        }
        List<DateOverride> responseOverrides = overrides.stream().map(override -> new DateOverride(
                override.getLocalDate(), override.getKind(),
                intervals.getOrDefault(override.getId(), List.of()).stream()
                        .sorted(Comparator.comparingInt(FactoryDateOverrideInterval::getStartMinute))
                        .map(i -> new OverrideInterval(formatMinute(i.getStartMinute()), formatMinute(i.getEndMinute())))
                        .toList())).toList();
        return new Detail(calendar.getFactoryId(), calendar.getFactory().getName(), calendar.getTimezoneId(),
                calendar.getResumeGraceSeconds(), calendar.getRevision(), weekly, responseOverrides);
    }

    private static Summary toSummary(Factory factory, FactoryOperatingCalendar calendar) {
        if (calendar == null) {
            return new Summary(factory.getId(), factory.getName(), DEFAULT_TIMEZONE,
                    DEFAULT_RESUME_GRACE_SECONDS, 0);
        }
        return new Summary(factory.getId(), factory.getName(), calendar.getTimezoneId(),
                calendar.getResumeGraceSeconds(), calendar.getRevision());
    }

    private static Detail defaultDetail(Factory factory) {
        List<Interval> weekly = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) weekly.add(new Interval(day, "00:00", "24:00"));
        return new Detail(factory.getId(), factory.getName(), DEFAULT_TIMEZONE,
                DEFAULT_RESUME_GRACE_SECONDS, 0, List.copyOf(weekly), List.of());
    }

    private User requireAdmin(String employeeId) {
        User user = accessControlService.getUser(employeeId);
        if (user.getRole() != Role.SYSTEM_ADMIN && user.getRole() != Role.FACTORY_ADMIN) {
            throw new AccessDeniedException("운영 캘린더 관리 권한이 없어요");
        }
        return user;
    }

    private User requireReader(String employeeId) {
        User user = accessControlService.getUser(employeeId);
        if (user.getRole() != Role.SYSTEM_ADMIN
                && user.getRole() != Role.SYSTEM_VIEWER
                && user.getRole() != Role.FACTORY_ADMIN) {
            throw new AccessDeniedException("운영 캘린더 조회 권한이 없어요");
        }
        return user;
    }

    private Factory getFactory(Long factoryId) {
        return factoryRepository.findById(factoryId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "존재하지 않는 공장이에요"));
    }

    private static void assertScope(User user, Long factoryId) {
        if (user.getRole().hasGlobalReadScope()) return;
        if (user.getFactory() == null || !factoryId.equals(user.getFactory().getId())) {
            throw new AccessDeniedException("본인 공장의 운영 캘린더만 관리할 수 있어요");
        }
    }

    private static ValidatedCalendar validate(ReplaceRequest request) {
        if (request == null || request.timezone() == null || request.resumeGraceSeconds() == null
                || request.revision() == null || request.weeklyIntervals() == null || request.dateOverrides() == null) {
            throw new IllegalArgumentException("캘린더의 모든 필드를 입력해 주세요");
        }
        if (!"UTC".equals(request.timezone()) && !ZoneId.getAvailableZoneIds().contains(request.timezone())) {
            throw new IllegalArgumentException("유효한 IANA timezone을 입력해 주세요");
        }
        if (request.resumeGraceSeconds() < 0 || request.resumeGraceSeconds() > 86400) {
            throw new IllegalArgumentException("재개 유예는 0~86400초여야 해요");
        }
        if (request.revision() < 0) throw new IllegalArgumentException("revision은 0 이상이어야 해요");
        if (request.dateOverrides().size() > MAX_OVERRIDES) {
            throw new IllegalArgumentException("날짜 예외는 최대 1000개까지 저장할 수 있어요");
        }

        Map<DayOfWeek, List<ValidatedInterval>> byDay = new EnumMap<>(DayOfWeek.class);
        for (Interval source : request.weeklyIntervals()) {
            if (source == null || source.dayOfWeek() == null) throw new IllegalArgumentException("요일을 입력해 주세요");
            byDay.computeIfAbsent(source.dayOfWeek(), ignored -> new ArrayList<>())
                    .add(parseInterval(source.start(), source.end()));
        }
        List<ValidatedWeekly> weekly = new ArrayList<>();
        for (Map.Entry<DayOfWeek, List<ValidatedInterval>> entry : byDay.entrySet()) {
            validateDay(entry.getValue(), "주간 일정");
            entry.getValue().forEach(i -> weekly.add(new ValidatedWeekly(entry.getKey(), i.startMinute(), i.endMinute())));
        }
        weekly.sort(Comparator.comparing((ValidatedWeekly i) -> i.day().getValue())
                .thenComparingInt(ValidatedWeekly::startMinute));

        Set<LocalDate> dates = new HashSet<>();
        List<ValidatedOverride> overrides = new ArrayList<>();
        for (DateOverride source : request.dateOverrides()) {
            if (source == null || source.date() == null || source.kind() == null || source.intervals() == null) {
                throw new IllegalArgumentException("날짜 예외 형식이 올바르지 않아요");
            }
            if (!dates.add(source.date())) throw new IllegalArgumentException("같은 날짜의 예외가 중복됐어요");
            List<ValidatedInterval> intervals = source.intervals().stream()
                    .map(i -> {
                        if (i == null) throw new IllegalArgumentException("예외 구간 형식이 올바르지 않아요");
                        return parseInterval(i.start(), i.end());
                    }).sorted(Comparator.comparingInt(ValidatedInterval::startMinute)).toList();
            if (source.kind() == FactoryDateOverride.Kind.CLOSED && !intervals.isEmpty()) {
                throw new IllegalArgumentException("휴무일에는 운영 구간을 넣을 수 없어요");
            }
            if (source.kind() == FactoryDateOverride.Kind.OPEN && intervals.isEmpty()) {
                throw new IllegalArgumentException("특근일에는 운영 구간이 하나 이상 필요해요");
            }
            validateDay(intervals, "날짜 예외");
            overrides.add(new ValidatedOverride(source.date(), source.kind(), intervals));
        }
        overrides.sort(Comparator.comparing(ValidatedOverride::date));
        return new ValidatedCalendar(request.timezone(), request.resumeGraceSeconds(), request.revision(), weekly, overrides);
    }

    private static ValidatedInterval parseInterval(String start, String end) {
        int startMinute = parseMinute(start, false);
        int endMinute = parseMinute(end, true);
        if (startMinute >= endMinute) {
            throw new IllegalArgumentException("운영 구간은 자정을 넘길 수 없고 시작이 종료보다 빨라야 해요");
        }
        return new ValidatedInterval(startMinute, endMinute);
    }

    private static int parseMinute(String value, boolean allowEndOfDay) {
        if (value == null) throw new IllegalArgumentException("시간을 입력해 주세요");
        Matcher matcher = TIME_PATTERN.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("시간은 HH:mm 형식이어야 해요");
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (allowEndOfDay && hour == 24 && minute == 0) return 1440;
        if (hour > 23 || minute > 59) throw new IllegalArgumentException("유효한 분 단위 시간을 입력해 주세요");
        return hour * 60 + minute;
    }

    private static void validateDay(List<ValidatedInterval> intervals, String label) {
        if (intervals.size() > MAX_INTERVALS_PER_DAY) {
            throw new IllegalArgumentException(label + "은 하루 최대 12개 구간까지 저장할 수 있어요");
        }
        List<ValidatedInterval> sorted = intervals.stream()
                .sorted(Comparator.comparingInt(ValidatedInterval::startMinute)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startMinute() <= sorted.get(i - 1).endMinute()) {
                throw new IllegalArgumentException(label + "의 구간은 겹치거나 맞닿을 수 없어요");
            }
        }
    }

    private static String formatMinute(int minute) {
        if (minute == 1440) return "24:00";
        return "%02d:%02d".formatted(minute / 60, minute % 60);
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("다른 관리자가 운영 캘린더를 수정했어요. 새로고침 후 다시 시도해 주세요");
    }

    private record ValidatedInterval(int startMinute, int endMinute) { }
    private record ValidatedWeekly(DayOfWeek day, int startMinute, int endMinute) { }
    private record ValidatedOverride(LocalDate date, FactoryDateOverride.Kind kind,
                                     List<ValidatedInterval> intervals) { }
    private record ValidatedCalendar(String timezone, int resumeGraceSeconds, long revision,
                                     List<ValidatedWeekly> weekly, List<ValidatedOverride> overrides) { }
}
