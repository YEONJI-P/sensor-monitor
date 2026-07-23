package dev.bugi.sensor.sensordata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.bugi.sensor.alert.entity.Alert;
import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.alert.repository.AlarmAcknowledgementRepository;
import dev.bugi.sensor.alert.repository.AlertRepository;
import dev.bugi.sensor.alert.service.AlarmNotificationFactory;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.entity.SensorChannel.ThresholdDirection;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.device.repository.SensorChannelRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sensordata.anomaly.AnomalyDetector;
import dev.bugi.sensor.sensordata.anomaly.ThresholdDetector;
import dev.bugi.sensor.sensordata.dto.BatchIngestRequest;
import dev.bugi.sensor.sensordata.dto.BatchIngestResult;
import dev.bugi.sensor.sensordata.dto.BatchSsePayload;
import dev.bugi.sensor.sensordata.entity.IngestOutcome;
import dev.bugi.sensor.sensordata.entity.IngestReceipt;
import dev.bugi.sensor.sensordata.entity.MeasurementBatch;
import dev.bugi.sensor.sensordata.entity.SensorReading;
import dev.bugi.sensor.sensordata.failure.FailedReading;
import dev.bugi.sensor.sensordata.failure.FailedReadingRepository;
import dev.bugi.sensor.sensordata.repository.MeasurementBatchRepository;
import dev.bugi.sensor.sensordata.repository.SensorReadingRepository;
import dev.bugi.sensor.sse.SseBroadcastEvent;
import dev.bugi.sensor.user.entity.Role;
import dev.bugi.sensor.user.entity.User;
import dev.bugi.sensor.user.entity.UserStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SensorDataServiceTest {

    @Mock DeviceRepository deviceRepository;
    @Mock DeviceStatusRepository deviceStatusRepository;
    @Mock SensorChannelRepository sensorChannelRepository;
    @Mock ChannelStatusRepository channelStatusRepository;
    @Mock MeasurementBatchRepository measurementBatchRepository;
    @Mock SensorReadingRepository sensorReadingRepository;
    @Mock AlertRepository alertRepository;
    @Mock AlarmEpisodeRepository alarmEpisodeRepository;
    @Mock AlarmAcknowledgementRepository alarmAcknowledgementRepository;
    @Spy AlarmNotificationFactory alarmNotificationFactory = new AlarmNotificationFactory();
    @Mock FailedReadingRepository failedReadingRepository;
    @Mock IngestReceiptService ingestReceiptService;
    @Spy AnomalyDetector anomalyDetector = new ThresholdDetector();
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock AccessControlService accessControlService;
    @Spy ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    @Mock EntityManager entityManager;
    @Mock Clock clock;

    @Captor ArgumentCaptor<Alert> alertCaptor;

    @InjectMocks
    SensorDataService sensorDataService;

    private static final Instant FIXED = Instant.parse("2026-07-16T00:00:00Z");

    // 가변 시각 홀더. clock.instant() 호출 횟수와 무관하게 지금 시각을 통제한다(쿨다운 경과 재현용).
    private final Instant[] now = { FIXED };

    private Device device;
    private SensorChannel channel;
    private ChannelStatus channelStatus; // receive 호출 간 알람 상태를 이어가려고 같은 인스턴스를 돌려준다.
    private long nextBatchId;

    @BeforeEach
    void stubClock() {
        lenient().when(clock.instant()).thenAnswer(inv -> now[0]);
    }

    /** 정상 저장 경로에 필요한 스텁을 세팅한다(테스트별 미사용 가능성 때문에 lenient). */
    private void arrange(Double threshold) {
        arrange(threshold, ThresholdDirection.ABOVE);
    }

    private void arrange(Double threshold, ThresholdDirection direction) {
        device = Device.builder().zone(null).code("CMAPSS-U1").name("엔진 유닛1")
                .location("C-MAPSS unit1").expectedIntervalSeconds(10).build();
        ReflectionTestUtils.setField(device, "id", 1L);
        channel = SensorChannel.builder().device(device).code("s4").unit("°R")
                .quantityKind("temperature").thresholdValue(threshold)
                .thresholdDirection(direction).build();
        ReflectionTestUtils.setField(channel, "id", 10L);
        channelStatus = new ChannelStatus(channel);
        ReflectionTestUtils.setField(channelStatus, "channelId", 10L);
        nextBatchId = 1L;

        lenient().when(deviceRepository.findByCode("CMAPSS-U1")).thenReturn(Optional.of(device));
        lenient().when(sensorChannelRepository.findByDeviceId(any())).thenReturn(List.of(channel));
        // 상태 일괄 로드: 같은 인스턴스를 돌려줘 receive 호출 간 알람 상태가 이어진다.
        lenient().when(channelStatusRepository.findAllByIdInForUpdateOrderByChannelId(any()))
                .thenReturn(List.of(channelStatus));
        lenient().when(alarmEpisodeRepository.findOpenThresholdForUpdate(any()))
                .thenAnswer(inv -> Optional.ofNullable(channelStatus.getActiveEpisode())
                        .filter(AlarmEpisode::isOpen));
        lenient().when(alarmEpisodeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(deviceStatusRepository.advanceLastSeenAt(any(), any())).thenReturn(1);
        lenient().when(measurementBatchRepository.save(any())).thenAnswer(inv -> {
            MeasurementBatch batch = inv.getArgument(0);
            ReflectionTestUtils.setField(batch, "id", nextBatchId++);
            return batch;
        });
        lenient().when(sensorReadingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(channelStatusRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(deviceStatusRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private BatchIngestRequest req(double value) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("s4", value);
        return new BatchIngestRequest("CMAPSS-U1", null, null, m);
    }

    private BatchIngestRequest reqMap(Map<String, Double> measurements) {
        return new BatchIngestRequest("CMAPSS-U1", null, null, measurements);
    }

    // ── 저장 · 부분 실패 · 상태코드 ─────────────────────────────────────

    @Test
    void 정상수신은_batch와_reading을_저장하고_SAVED를_반환한다() {
        arrange(1416.0);

        BatchIngestResult result = sensorDataService.receive(req(1000.0));

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.SAVED);
        assertThat(result.response().savedCount()).isEqualTo(1);
        assertThat(result.response().rejected()).isEmpty();
        verify(measurementBatchRepository, times(1)).save(any(MeasurementBatch.class));
        verify(sensorReadingRepository, times(1)).save(any(SensorReading.class));
    }

    @Test
    void 정상값은_alert를_만들지_않는다() {
        arrange(1416.0);

        sensorDataService.receive(req(1000.0));

        verify(alertRepository, never()).save(any());
    }

    @Test
    void 표시전용_null임계채널은_어떤_값에도_alert를_만들지_않는다() {
        arrange(null);

        sensorDataService.receive(req(-1_000_000.0));
        sensorDataService.receive(req(1_000_000.0));

        verify(alertRepository, never()).save(any());
        assertThat(channelStatus.isInAlarm()).isFalse();
    }

    @Test
    void 장치코드_미존재는_실패적재_1행과_404를_반환하고_batch를_만들지_않는다() {
        when(deviceRepository.findByCode("NOPE")).thenReturn(Optional.empty());
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("s4", 100.0);

        BatchIngestResult result = sensorDataService.receive(new BatchIngestRequest("NOPE", null, null, m));

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.DEVICE_NOT_FOUND);
        verify(failedReadingRepository, times(1)).save(any(FailedReading.class));
        verify(measurementBatchRepository, never()).save(any());
    }

    @Test
    void 미지_채널은_거부하고_알려진_채널만_저장한다_200() {
        arrange(1416.0);
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("s4", 1000.0);
        m.put("bogus", 5.0);

        BatchIngestResult result = sensorDataService.receive(reqMap(m));

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.SAVED);
        assertThat(result.response().savedCount()).isEqualTo(1);
        assertThat(result.response().rejected()).hasSize(1);
        assertThat(result.response().rejected().get(0).channelCode()).isEqualTo("bogus");
        assertThat(result.response().rejected().get(0).reason()).isEqualTo("UNKNOWN_CHANNEL");
        verify(measurementBatchRepository, times(1)).save(any());
        verify(sensorReadingRepository, times(1)).save(any());
        // 거부 판독은 saveAll 로 한 번에 적재한다.
        verify(failedReadingRepository, times(1)).saveAll(any());
        verify(failedReadingRepository, never()).save(any());
    }

    @Test
    void 미지_채널_실패적재에_deviceId가_채워진다() {
        // freshness 원인진단(countByDeviceIdAndCreatedAtAfter)이 세려면 deviceId 가 있어야 한다.
        Device dev = mock(Device.class);
        when(dev.getId()).thenReturn(42L);
        SensorChannel ch = SensorChannel.builder().device(dev).code("s4").unit("°R")
                .quantityKind("temperature").thresholdValue(1416.0)
                .thresholdDirection(ThresholdDirection.ABOVE).build();
        ReflectionTestUtils.setField(ch, "id", 10L);
        when(deviceRepository.findByCode("CMAPSS-U1")).thenReturn(Optional.of(dev));
        when(sensorChannelRepository.findByDeviceId(42L)).thenReturn(List.of(ch));
        when(measurementBatchRepository.save(any())).thenAnswer(inv -> {
            MeasurementBatch batch = inv.getArgument(0);
            ReflectionTestUtils.setField(batch, "id", 1L);
            return batch;
        });
        when(deviceStatusRepository.advanceLastSeenAt(42L, FIXED)).thenReturn(1);
        ChannelStatus status = new ChannelStatus(ch);
        ReflectionTestUtils.setField(status, "channelId", 10L);
        when(channelStatusRepository.findAllByIdInForUpdateOrderByChannelId(any()))
                .thenReturn(List.of(status));
        when(alarmEpisodeRepository.findOpenThresholdForUpdate(any())).thenReturn(Optional.empty());

        Map<String, Double> m = new LinkedHashMap<>();
        m.put("s4", 100.0);     // known
        m.put("bogus", 5.0);    // unknown
        sensorDataService.receive(new BatchIngestRequest("CMAPSS-U1", null, null, m));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FailedReading>> captor = ArgumentCaptor.forClass(List.class);
        verify(failedReadingRepository).saveAll(captor.capture());
        FailedReading failed = captor.getValue().get(0);
        assertThat(failed.getReason()).isEqualTo("UNKNOWN_CHANNEL");
        assertThat(failed.getChannelCode()).isEqualTo("bogus");
        assertThat(failed.getDeviceCode()).isEqualTo("CMAPSS-U1");
        assertThat(failed.getDeviceId()).isEqualTo(42L);
    }

    @Test
    void 전_채널_미지는_batch미생성_markSeen없음_422() {
        arrange(1416.0);
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("bogus", 5.0);

        BatchIngestResult result = sensorDataService.receive(reqMap(m));

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.NO_KNOWN_CHANNELS);
        assertThat(result.response().batchId()).isNull();
        verify(measurementBatchRepository, never()).save(any());
        verify(deviceStatusRepository, never()).advanceLastSeenAt(any(), any()); // markSeen 안 함
        verify(failedReadingRepository, times(1)).saveAll(any());
    }

    @Test
    void null값은_NULL_VALUE로_거부된다() {
        arrange(1416.0);
        Map<String, Double> m = new HashMap<>();
        m.put("s4", null);

        BatchIngestResult result = sensorDataService.receive(reqMap(m));

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.NO_KNOWN_CHANNELS);
        assertThat(result.response().rejected()).hasSize(1);
        assertThat(result.response().rejected().get(0).reason()).isEqualTo("NULL_VALUE");
    }

    // ── 하트비트 ────────────────────────────────────────────────────────

    @Test
    void 정상_수신이면_DeviceStatus의_lastSeenAt을_원자적으로_전진시킨다() {
        arrange(1416.0);

        sensorDataService.receive(req(1000.0));

        verify(deviceStatusRepository).advanceLastSeenAt(device.getId(), FIXED);
    }

    @Test
    void DeviceStatus가_없으면_invariant_오류로_전체_수신을_실패시킨다() {
        arrange(1416.0);
        when(deviceStatusRepository.advanceLastSeenAt(any(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> sensorDataService.receive(req(1000.0)));
    }

    // ── SSE 브로드캐스트 ────────────────────────────────────────────────

    @Test
    void 정상수신은_sensordata_이벤트만_발행한다() {
        arrange(1416.0);

        sensorDataService.receive(req(1000.0));

        ArgumentCaptor<SseBroadcastEvent> captor = ArgumentCaptor.forClass(SseBroadcastEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().event()).isEqualTo("sensor-data");
    }

    @Test
    void 발화하면_sensordata와_alert_이벤트를_모두_발행한다() {
        arrange(100.0);

        sensorDataService.receive(req(200.0));

        ArgumentCaptor<SseBroadcastEvent> captor = ArgumentCaptor.forClass(SseBroadcastEvent.class);
        verify(eventPublisher, times(3)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(SseBroadcastEvent::event)
                .containsExactly("alarm-episode", "sensor-data", "alert");
    }

    // ── 엣지 트리거 쿨다운 ──────────────────────────────────────────────

    @Test
    void 첫_초과는_발화하고_알람상태로_전환된다() {
        arrange(100.0);

        sensorDataService.receive(req(200.0));

        verify(alertRepository, times(1)).save(any(Alert.class));
        assertThat(channelStatus.isInAlarm()).isTrue();
        assertThat(channelStatus.getLastAlertAt()).isEqualTo(FIXED);
    }

    @Test
    void BELOW_알림은_임계값_미만으로_표현한다() {
        arrange(100.0, ThresholdDirection.BELOW);

        sensorDataService.receive(req(50.0));

        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getMessage()).contains("임계값 미만");
    }

    @Test
    void ABS_ABOVE_알림은_절댓값_초과로_표현한다() {
        arrange(100.0, ThresholdDirection.ABS_ABOVE);

        sensorDataService.receive(req(-150.0));

        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getMessage()).contains("임계값 절댓값 초과");
    }

    @Test
    void legacy_null방향은_ABOVE_알림문구로_fallback한다() {
        arrange(100.0, null);

        sensorDataService.receive(req(150.0));

        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getMessage()).contains("임계값 초과");
    }

    @Test
    void 연속_초과는_한번만_발화하고_이후_억제된다() {
        arrange(100.0);

        sensorDataService.receive(req(200.0)); // 발화
        sensorDataService.receive(req(205.0)); // 억제
        sensorDataService.receive(req(210.0)); // 억제

        verify(alertRepository, times(1)).save(any(Alert.class));
    }

    @Test
    void 여유구간에서는_해제도_재발화도_없다() {
        arrange(100.0); // 해제 경계 = 99.9

        sensorDataService.receive(req(200.0)); // 발화 → inAlarm
        sensorDataService.receive(req(99.95)); // 여유구간(99.9~100): 초과 아님이나 해제도 안 함
        assertThat(channelStatus.isInAlarm()).isTrue();

        sensorDataService.receive(req(200.0)); // 여전히 inAlarm → 억제

        verify(alertRepository, times(1)).save(any(Alert.class));
    }

    @Test
    void 여유구간_아래로_복귀하고_쿨다운_지나면_재발화한다() {
        arrange(100.0);

        sensorDataService.receive(req(200.0)); // 발화(FIXED)
        sensorDataService.receive(req(90.0));  // 97 미만 → 해제
        assertThat(channelStatus.isInAlarm()).isFalse();

        now[0] = FIXED.plus(Duration.ofMinutes(6)); // 쿨다운 5분 경과
        sensorDataService.receive(req(200.0));       // 재발화

        verify(alertRepository, times(2)).save(any(Alert.class));
    }

    @Test
    void 쿨다운_이내_산발스파이크는_재발화하지_않는다() {
        arrange(100.0);

        sensorDataService.receive(req(200.0)); // 발화(FIXED)
        sensorDataService.receive(req(90.0));  // 해제

        now[0] = FIXED.plus(Duration.ofMinutes(1)); // 쿨다운 5분 이내
        sensorDataService.receive(req(200.0));       // 억제(산발 스파이크 취급)

        verify(alertRepository, times(1)).save(any(Alert.class));
        assertThat(channelStatus.isInAlarm()).isTrue();
        assertThat(channelStatus.getActiveEpisode()).isNotNull();
    }

    @Test
    void 지속_breach는_쿨다운_경과_후_다음_reading에서_REMINDER를_만든다() {
        arrange(100.0);

        sensorDataService.receive(req(105.0));
        now[0] = FIXED.plus(Duration.ofMinutes(5));
        sensorDataService.receive(req(106.0));

        verify(alertRepository, times(2)).save(alertCaptor.capture());
        assertThat(alertCaptor.getAllValues().get(1).getNotificationReason())
                .isEqualTo(AlertNotificationReason.REMINDER);
    }

    @Test
    void 현재_severity를_ACK하면_지속_breach_REMINDER를_억제한다() {
        arrange(100.0);

        sensorDataService.receive(req(105.0));
        AlarmAcknowledgement acknowledgement = mock(AlarmAcknowledgement.class);
        when(acknowledgement.getCreatedAt()).thenReturn(FIXED.plusSeconds(1));
        when(acknowledgement.getAckSeverity()).thenReturn(AlertSeverity.WARNING);
        when(alarmAcknowledgementRepository.findFirstByEpisodeIdOrderByCreatedAtDesc(any()))
                .thenReturn(Optional.of(acknowledgement));

        now[0] = FIXED.plus(Duration.ofMinutes(5));
        sensorDataService.receive(req(106.0));

        verify(alertRepository, times(1)).save(any(Alert.class));
    }

    @Test
    void 같은_observedAt_cursor는_더_큰_batchId만_허용한다() {
        arrange(100.0);
        channelStatus.markEvaluated(FIXED, 20L);

        assertThat(channelStatus.shouldEvaluate(FIXED, 19L)).isFalse();
        assertThat(channelStatus.shouldEvaluate(FIXED, 20L)).isFalse();
        assertThat(channelStatus.shouldEvaluate(FIXED, 21L)).isTrue();
    }

    @Test
    void WARNING에서_CRITICAL로_상승하면_쿨다운_중에도_즉시_escalation한다() {
        arrange(100.0);

        sensorDataService.receive(req(105.0));
        now[0] = FIXED.plus(Duration.ofMinutes(1));
        sensorDataService.receive(req(200.0));

        verify(alertRepository, times(2)).save(alertCaptor.capture());
        Alert escalated = alertCaptor.getAllValues().get(1);
        assertThat(escalated.getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
        assertThat(escalated.getNotificationReason())
                .isEqualTo(AlertNotificationReason.SEVERITY_ESCALATION);
    }

    @Test
    void 역행_reading은_저장과_SSE에는_남지만_state와_episode에는_적용하지_않는다() {
        arrange(100.0);
        sensorDataService.receive(new BatchIngestRequest(
                "CMAPSS-U1", FIXED, null, Map.of("s4", 105.0)));

        BatchIngestResult late = sensorDataService.receive(new BatchIngestRequest(
                "CMAPSS-U1", FIXED.minusSeconds(1), null, Map.of("s4", 50.0)));

        assertThat(late.response().savedCount()).isOne();
        assertThat(late.response().stateAppliedCount()).isZero();
        assertThat(channelStatus.isInAlarm()).isTrue();
        assertThat(channelStatus.getLastEvaluatedObservedAt()).isEqualTo(FIXED);
        verify(sensorReadingRepository, times(2)).save(any());

        ArgumentCaptor<SseBroadcastEvent> eventCaptor =
                ArgumentCaptor.forClass(SseBroadcastEvent.class);
        verify(eventPublisher, times(4)).publishEvent(eventCaptor.capture());
        SseBroadcastEvent lateSensorEvent = eventCaptor.getAllValues().get(3);
        BatchSsePayload payload = (BatchSsePayload) lateSensorEvent.payload();
        assertThat(payload.readings()).singleElement()
                .extracting(BatchSsePayload.Reading::stateApplied).isEqualTo(false);
    }

    @Test
    void 미래_관측시각은_failed_reading만_남기고_batch와_heartbeat를_만들지_않는다() {
        arrange(100.0);
        BatchIngestRequest request = new BatchIngestRequest(
                "CMAPSS-U1", FIXED.plus(Duration.ofMinutes(5)).plusMillis(1),
                7L, Map.of("s4", 105.0));

        BatchIngestResult result = sensorDataService.receive(request);

        assertThat(result.outcome()).isEqualTo(BatchIngestResult.Outcome.FUTURE_OBSERVED_AT);
        assertThat(result.response().stateAppliedCount()).isZero();
        verify(failedReadingRepository).save(argThat(
                failed -> failed.getReason().equals("FUTURE_OBSERVED_AT")));
        verify(measurementBatchRepository, never()).save(any());
        verify(deviceStatusRepository, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 동일_eventId_replay는_저장과_SSE없이_최초_응답을_그대로_반환한다() {
        arrange(100.0);
        IngestReceipt receipt = new IngestReceipt(
                "CMAPSS-U1", "evt-1", "hash", FIXED.minusSeconds(1));
        receipt.complete(IngestOutcome.SAVED, 200, Map.of(
                "batchId", 77,
                "deviceId", 1,
                "deviceCode", "CMAPSS-U1",
                "observedAt", "2026-07-16T00:00:00Z",
                "receivedAt", "2026-07-16T00:00:00Z",
                "savedCount", 1,
                "rejected", List.of(),
                "eventId", "evt-1",
                "stateAppliedCount", 1));
        when(ingestReceiptService.claim(eq("CMAPSS-U1"), eq("evt-1"), any()))
                .thenReturn(new IngestReceiptService.Claim(receipt, false));

        BatchIngestResult replayed = sensorDataService.receive(new BatchIngestRequest(
                "CMAPSS-U1", FIXED, 1L, Map.of("s4", 105.0), "evt-1"));

        assertThat(replayed.response().batchId()).isEqualTo(77L);
        assertThat(replayed.response().eventId()).isEqualTo("evt-1");
        assertThat(replayed.response().stateAppliedCount()).isOne();
        verify(measurementBatchRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── severity ────────────────────────────────────────────────────────

    @Test
    void 임계_1점1배_이하_초과는_WARNING() {
        arrange(100.0); // 110 이하면 WARNING

        sensorDataService.receive(req(105.0));

        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getSeverity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    void 임계_1점1배_초과는_CRITICAL() {
        arrange(100.0);

        sensorDataService.receive(req(200.0));

        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
    }

    @Test
    void severity_경계_정확히_임계1점1배는_WARNING() {
        arrange(100.0); // 100 * 1.1 = 110, value <= 110 이면 WARNING

        sensorDataService.receive(req(110.0));

        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getSeverity()).isEqualTo(AlertSeverity.WARNING);
    }

    @Test
    void severity_경계_임계1점1배_초과는_CRITICAL() {
        arrange(100.0);

        sensorDataService.receive(req(110.01));

        verify(alertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getSeverity()).isEqualTo(AlertSeverity.CRITICAL);
    }

    // ── 히스테리시스(해제) 경계 ─────────────────────────────────────────

    @Test
    void 해제경계_정확히_임계0점999배면_알람유지() {
        arrange(100.0); // 100 * 0.999 = 99.9, 해제 조건은 value < 99.9 이므로 경계는 유지

        sensorDataService.receive(req(200.0)); // 발화 → inAlarm
        sensorDataService.receive(req(99.9));  // 경계값 → 해제 안 됨

        assertThat(channelStatus.isInAlarm()).isTrue();
    }

    @Test
    void 해제경계_임계0점999배_미만이면_알람해제() {
        arrange(100.0);

        sensorDataService.receive(req(200.0)); // 발화 → inAlarm
        sensorDataService.receive(req(99.89)); // 경계 아래 → 해제

        assertThat(channelStatus.isInAlarm()).isFalse();
    }

    @Test
    void C_MAPSS_offset_채널은_정상값으로_복귀하면_알람해제() {
        arrange(643.4);

        sensorDataService.receive(req(644.1));
        sensorDataService.receive(req(642.4));

        assertThat(channelStatus.isInAlarm()).isFalse();
    }

    @Test
    void factory_admin_getReadings_same_factory_allowed() {
        User admin = User.builder().employeeId("ADMIN").name("공장 관리자").password("pw")
                .role(Role.FACTORY_ADMIN).status(UserStatus.ACTIVE).build();
        SensorChannel target = SensorChannel.builder().code("s4").thresholdValue(80.0)
                .thresholdDirection(ThresholdDirection.ABOVE).build();
        when(accessControlService.getUser("ADMIN")).thenReturn(admin);
        when(accessControlService.getChannel(1L)).thenReturn(target);
        when(sensorReadingRepository.findByChannelIdOrderByObservedAtDesc(eq(1L), any()))
                .thenReturn(List.of());

        assertThat(sensorDataService.getReadingsByChannel("ADMIN", 1L, 50)).isEmpty();
        verify(accessControlService).assertCanAccessChannel(admin, target);
    }

    @Test
    void factory_admin_getReadings_other_factory_forbidden() {
        User admin = User.builder().employeeId("ADMIN").name("공장 관리자").password("pw")
                .role(Role.FACTORY_ADMIN).status(UserStatus.ACTIVE).build();
        SensorChannel target = SensorChannel.builder().code("s4").build();
        when(accessControlService.getUser("ADMIN")).thenReturn(admin);
        when(accessControlService.getChannel(2L)).thenReturn(target);
        doThrow(new AccessDeniedException("접근 권한이 없는 장치예요"))
                .when(accessControlService).assertCanAccessChannel(admin, target);

        assertThrows(AccessDeniedException.class,
                () -> sensorDataService.getReadingsByChannel("ADMIN", 2L, 50));
        verify(sensorReadingRepository, never()).findByChannelIdOrderByObservedAtDesc(any(), any());
    }
}
