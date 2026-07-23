package dev.bugi.sensor.sensordata.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3 SSE payload(event: sensor-data)의 직렬화 형태를 고정한다.
 * 프론트 트랙은 이 필드명 계약만 참조하므로, 이름이 바뀌면 여기서 즉시 깨져야 한다.
 *
 * ObjectMapper 설정은 application.yml 과 같다(JavaTimeModule + write-dates-as-timestamps=false):
 * Instant 는 epoch 숫자가 아니라 ISO-8601(끝에 Z) 문자열로 직렬화된다.
 */
class BatchSsePayloadSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void payload_필드명과_시각형식을_고정한다() throws Exception {
        BatchSsePayload payload = new BatchSsePayload(
                10L, 3L,
                Instant.parse("2026-07-18T00:00:00Z"),
                Instant.parse("2026-07-18T00:00:01Z"),
                List.of(new BatchSsePayload.Reading(5L, "s4", 1500.0, true)));

        JsonNode node = mapper.readTree(mapper.writeValueAsString(payload));

        // 최상위 필드명(선언 순서) 고정.
        assertThat(node.fieldNames()).toIterable()
                .containsExactly("batchId", "deviceId", "observedAt", "receivedAt", "readings");
        assertThat(node.get("batchId").asLong()).isEqualTo(10L);
        assertThat(node.get("deviceId").asLong()).isEqualTo(3L);
        // Instant → ISO-8601 문자열(Z).
        assertThat(node.get("observedAt").asText()).isEqualTo("2026-07-18T00:00:00Z");
        assertThat(node.get("receivedAt").asText()).isEqualTo("2026-07-18T00:00:01Z");

        // 각 reading 필드명 고정.
        JsonNode reading = node.get("readings").get(0);
        assertThat(reading.fieldNames()).toIterable()
                .containsExactly("channelId", "channelCode", "value", "anomaly", "stateApplied");
        assertThat(reading.get("channelId").asLong()).isEqualTo(5L);
        assertThat(reading.get("channelCode").asText()).isEqualTo("s4");
        assertThat(reading.get("value").asDouble()).isEqualTo(1500.0);
        assertThat(reading.get("anomaly").asBoolean()).isTrue();
        assertThat(reading.get("stateApplied").asBoolean()).isTrue();
    }
}
