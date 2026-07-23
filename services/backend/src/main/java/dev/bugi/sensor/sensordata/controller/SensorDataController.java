package dev.bugi.sensor.sensordata.controller;

import dev.bugi.sensor.sensordata.dto.BatchIngestRequest;
import dev.bugi.sensor.sensordata.dto.BatchIngestResponse;
import dev.bugi.sensor.sensordata.dto.BatchIngestResult;
import dev.bugi.sensor.sensordata.service.SensorDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sensor-data")
@RequiredArgsConstructor
public class SensorDataController {

    private final SensorDataService sensorDataService;

    // 외부 장치 입력(C1). ingest 공유 키 인증 후 부분 실패(200)·장치 없음(404)·전 채널 미지(422)를 결과로 매핑한다.
    // (channel 별 조회는 GET /channels/{id}/readings 로 옮겼다.)
    @PostMapping
    public ResponseEntity<BatchIngestResponse> receive(@RequestBody @Valid BatchIngestRequest request) {
        BatchIngestResult result = sensorDataService.receive(request);
        HttpStatus status = switch (result.outcome()) {
            case SAVED -> HttpStatus.OK;
            case DEVICE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case NO_KNOWN_CHANNELS, FUTURE_OBSERVED_AT -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(result.response());
    }
}
