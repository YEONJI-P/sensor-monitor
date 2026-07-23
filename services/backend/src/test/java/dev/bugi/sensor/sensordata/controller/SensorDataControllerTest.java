package dev.bugi.sensor.sensordata.controller;

import dev.bugi.sensor.sensordata.dto.BatchIngestRequest;
import dev.bugi.sensor.sensordata.dto.BatchIngestResult;
import dev.bugi.sensor.sensordata.service.SensorDataService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensorDataControllerTest {

    @Test
    void future_observed_at은_422로_매핑한다() {
        SensorDataService service = mock(SensorDataService.class);
        SensorDataController controller = new SensorDataController(service);
        Instant receivedAt = Instant.parse("2026-07-23T01:00:00Z");
        Instant observedAt = receivedAt.plusSeconds(301);
        BatchIngestRequest request = new BatchIngestRequest(
                "D-1", observedAt, 1L, Map.of("temp", 10.0), "evt-1");
        when(service.receive(request)).thenReturn(BatchIngestResult.futureObservedAt(
                "D-1", observedAt, receivedAt, "evt-1"));

        var response = controller.receive(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().eventId()).isEqualTo("evt-1");
        assertThat(response.getBody().stateAppliedCount()).isZero();
    }
}
