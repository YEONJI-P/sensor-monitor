package dev.bugi.sensor.alert.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmScopeType;
import dev.bugi.sensor.alert.entity.AlarmType;
import dev.bugi.sensor.alert.entity.Alert;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertResponseSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void 기존_필드와_lifecycle_projection을_함께_직렬화한다() throws Exception {
        Alert alert = mock(Alert.class);
        AlarmEpisode episode = mock(AlarmEpisode.class);
        when(alert.getId()).thenReturn(9L);
        when(alert.getMessage()).thenReturn("warning");
        when(alert.getSeverity()).thenReturn(AlertSeverity.WARNING);
        when(alert.getCreatedAt()).thenReturn(Instant.parse("2026-07-23T00:00:00Z"));
        when(alert.getAlarmType()).thenReturn(AlarmType.THRESHOLD);
        when(alert.getScopeType()).thenReturn(AlarmScopeType.CHANNEL);
        when(alert.getEpisode()).thenReturn(episode);
        when(episode.getId()).thenReturn(4L);
        when(alert.getNotificationReason()).thenReturn(AlertNotificationReason.INITIAL);

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsString(AlertResponse.from(alert)));

        assertThat(json.get("id").asLong()).isEqualTo(9L);
        assertThat(json.get("message").asText()).isEqualTo("warning");
        assertThat(json.get("type").asText()).isEqualTo("THRESHOLD");
        assertThat(json.get("scope").asText()).isEqualTo("CHANNEL");
        assertThat(json.get("episodeId").asLong()).isEqualTo(4L);
        assertThat(json.get("notificationReason").asText()).isEqualTo("INITIAL");
    }

    @Test
    void acknowledgement_projection은_스키마에_없는_revision을_노출하지_않는다() throws Exception {
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(
                new AlarmAcknowledgementResponse(
                        1L, 2L, "작업자", AlertSeverity.WARNING,
                        Instant.parse("2026-07-23T00:00:00Z"))));

        assertThat(json.get("id").asLong()).isEqualTo(1L);
        assertThat(json.get("ackSeverity").asText()).isEqualTo("WARNING");
        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "userId", "userName", "ackSeverity", "createdAt");
    }
}
