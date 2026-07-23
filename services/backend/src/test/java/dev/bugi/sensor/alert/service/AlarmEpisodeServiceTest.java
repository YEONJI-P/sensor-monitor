package dev.bugi.sensor.alert.service;

import dev.bugi.sensor.alert.dto.AlarmEpisodeResponse;
import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmScopeType;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.repository.AlarmAcknowledgementRepository;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.sse.SseBroadcastEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlarmEpisodeServiceTest {

    @Mock AlarmEpisodeRepository episodeRepository;
    @Mock AlarmAcknowledgementRepository acknowledgementRepository;
    @Mock AccessControlService accessControlService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Spy Clock clock = Clock.fixed(
            Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    @InjectMocks AlarmEpisodeService service;

    @Test
    void ack는_같은_severity에서_중복행을_만들지_않는다() {
        User actor = mock(User.class);
        Device device = mock(Device.class);
        AlarmEpisode episode = mock(AlarmEpisode.class);
        AlarmAcknowledgement existing = new AlarmAcknowledgement(
                episode, actor, AlertSeverity.WARNING, clock.instant());
        when(actor.getRole()).thenReturn(Role.MEMBER);
        when(device.getId()).thenReturn(7L);
        when(episode.getId()).thenReturn(11L);
        when(episode.getDevice()).thenReturn(device);
        when(episode.getScopeType()).thenReturn(AlarmScopeType.DEVICE);
        when(episode.getAlarmType()).thenReturn(AlarmType.DEVICE_SILENCE);
        when(episode.getCurrentSeverity()).thenReturn(AlertSeverity.WARNING);
        when(episode.getMaxSeverity()).thenReturn(AlertSeverity.WARNING);
        when(episode.getSnapshot()).thenReturn(Map.of());
        when(episode.isOpen()).thenReturn(true);
        when(accessControlService.getUser("EMP001")).thenReturn(actor);
        when(accessControlService.getAccessibleDeviceIds(actor)).thenReturn(List.of(7L));
        when(accessControlService.getAccessibleZones(actor)).thenReturn(List.of());
        when(episodeRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(episode));
        when(acknowledgementRepository.findFirstByEpisodeIdOrderByCreatedAtDesc(11L))
                .thenReturn(Optional.of(existing));

        AlarmEpisodeResponse response = service.acknowledge("EMP001", 11L);

        assertThat(response.latestAcknowledgement().ackSeverity())
                .isEqualTo(AlertSeverity.WARNING);
        verify(acknowledgementRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void ack는_severity가_상승하면_새_revision을_만든다() {
        User actor = mock(User.class);
        Device device = mock(Device.class);
        AlarmEpisode episode = mock(AlarmEpisode.class);
        AlarmAcknowledgement prior = new AlarmAcknowledgement(
                episode, actor, AlertSeverity.WARNING, clock.instant().minusSeconds(30));
        when(actor.getRole()).thenReturn(Role.MEMBER);
        when(device.getId()).thenReturn(7L);
        when(episode.getId()).thenReturn(11L);
        when(episode.getDevice()).thenReturn(device);
        when(episode.getScopeType()).thenReturn(AlarmScopeType.DEVICE);
        when(episode.getAlarmType()).thenReturn(AlarmType.DEVICE_SILENCE);
        when(episode.getCurrentSeverity()).thenReturn(AlertSeverity.CRITICAL);
        when(episode.getMaxSeverity()).thenReturn(AlertSeverity.CRITICAL);
        when(episode.getSnapshot()).thenReturn(Map.of());
        when(episode.isOpen()).thenReturn(true);
        when(accessControlService.getUser("EMP001")).thenReturn(actor);
        when(accessControlService.getAccessibleDeviceIds(actor)).thenReturn(List.of(7L));
        when(accessControlService.getAccessibleZones(actor)).thenReturn(List.of());
        when(episodeRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(episode));
        when(acknowledgementRepository.findFirstByEpisodeIdOrderByCreatedAtDesc(11L))
                .thenReturn(Optional.of(prior));
        when(acknowledgementRepository.save(any(AlarmAcknowledgement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AlarmEpisodeResponse response = service.acknowledge("EMP001", 11L);

        assertThat(response.latestAcknowledgement().ackSeverity())
                .isEqualTo(AlertSeverity.CRITICAL);
        verify(acknowledgementRepository).save(any(AlarmAcknowledgement.class));
        verify(eventPublisher).publishEvent(any(SseBroadcastEvent.class));
    }

    @Test
    void viewer는_service_경계에서도_ack가_거부된다() {
        User viewer = mock(User.class);
        when(viewer.getRole()).thenReturn(Role.VIEWER);
        when(accessControlService.getUser("VIEW")).thenReturn(viewer);

        assertThatThrownBy(() -> service.acknowledge("VIEW", 1L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(episodeRepository, acknowledgementRepository);
    }

    @Test
    void 목록의_device_filter가_접근범위를_벗어나면_거부한다() {
        User user = mock(User.class);
        when(accessControlService.getUser("EMP001")).thenReturn(user);
        when(accessControlService.getAccessibleDeviceIds(user)).thenReturn(List.of(7L));
        when(accessControlService.getAccessibleZones(user)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getEpisodes(
                "EMP001", null, null, null, 8L, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void 상세_episode가_다른_device_scope이면_거부한다() {
        User user = mock(User.class);
        AlarmEpisode episode = mock(AlarmEpisode.class);
        Device otherDevice = mock(Device.class);
        when(otherDevice.getId()).thenReturn(8L);
        when(episode.getScopeType()).thenReturn(AlarmScopeType.DEVICE);
        when(episode.getDevice()).thenReturn(otherDevice);
        when(accessControlService.getUser("EMP001")).thenReturn(user);
        when(accessControlService.getAccessibleDeviceIds(user)).thenReturn(List.of(7L));
        when(accessControlService.getAccessibleZones(user)).thenReturn(List.of());
        when(episodeRepository.findById(11L)).thenReturn(Optional.of(episode));

        assertThatThrownBy(() -> service.getEpisode("EMP001", 11L))
                .isInstanceOf(AccessDeniedException.class);
    }
}
