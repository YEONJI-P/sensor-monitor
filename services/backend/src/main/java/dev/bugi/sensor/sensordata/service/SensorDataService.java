package dev.bugi.sensor.sensordata.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bugi.sensor.alert.dto.AlertResponse;
import dev.bugi.sensor.alert.dto.AlarmEpisodeResponse;
import dev.bugi.sensor.alert.dto.AlarmEpisodeSsePayload;
import dev.bugi.sensor.alert.entity.AlarmEpisode;
import dev.bugi.sensor.alert.entity.AlarmResolutionReason;
import dev.bugi.sensor.alert.entity.Alert;
import dev.bugi.sensor.alert.entity.AlertNotificationReason;
import dev.bugi.sensor.alert.entity.AlertSeverity;
import dev.bugi.sensor.alert.repository.AlarmEpisodeRepository;
import dev.bugi.sensor.alert.repository.AlarmAcknowledgementRepository;
import dev.bugi.sensor.alert.repository.AlertRepository;
import dev.bugi.sensor.alert.service.AlarmNotificationFactory;
import dev.bugi.sensor.device.entity.ChannelStatus;
import dev.bugi.sensor.device.entity.Device;
import dev.bugi.sensor.device.entity.SensorChannel;
import dev.bugi.sensor.device.repository.ChannelStatusRepository;
import dev.bugi.sensor.device.repository.DeviceRepository;
import dev.bugi.sensor.device.repository.DeviceStatusRepository;
import dev.bugi.sensor.device.repository.SensorChannelRepository;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sensordata.anomaly.AnomalyDetector;
import dev.bugi.sensor.sensordata.dto.BatchIngestRequest;
import dev.bugi.sensor.sensordata.dto.BatchIngestResponse;
import dev.bugi.sensor.sensordata.dto.BatchIngestResult;
import dev.bugi.sensor.sensordata.dto.BatchSsePayload;
import dev.bugi.sensor.sensordata.dto.ReadingResponse;
import dev.bugi.sensor.sensordata.dto.RejectedReading;
import dev.bugi.sensor.sensordata.entity.IngestOutcome;
import dev.bugi.sensor.sensordata.entity.IngestReceipt;
import dev.bugi.sensor.sensordata.entity.MeasurementBatch;
import dev.bugi.sensor.sensordata.entity.SensorReading;
import dev.bugi.sensor.sensordata.failure.FailedReading;
import dev.bugi.sensor.sensordata.failure.FailedReadingRepository;
import dev.bugi.sensor.sensordata.repository.MeasurementBatchRepository;
import dev.bugi.sensor.sensordata.repository.SensorReadingRepository;
import dev.bugi.sensor.sse.SseBroadcastEvent;
import dev.bugi.sensor.user.entity.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SensorDataService {

    private static final int MAX_RECENT = 500; // 채널별 조회 상한

    // strict hysteresis: 경계값 자체는 해제하지 않고 여유 구간을 완전히 벗어난 값만 해제한다.
    private static final double RELEASE_RATIO = 0.999;
    // ABOVE 는 임계*CRITICAL_RATIO 이하 초과는 WARNING, 초과하면 CRITICAL.
    private static final double CRITICAL_RATIO = 1.1;
    // episode 전이는 즉시 수행하고 사용자 통지만 이 간격으로 제한한다.
    private static final Duration NOTIFICATION_COOLDOWN = Duration.ofMinutes(5);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final DeviceRepository deviceRepository;
    private final DeviceStatusRepository deviceStatusRepository;
    private final SensorChannelRepository sensorChannelRepository;
    private final ChannelStatusRepository channelStatusRepository;
    private final MeasurementBatchRepository measurementBatchRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final AlertRepository alertRepository;
    private final AlarmEpisodeRepository alarmEpisodeRepository;
    private final AlarmAcknowledgementRepository alarmAcknowledgementRepository;
    private final AlarmNotificationFactory alarmNotificationFactory;
    private final FailedReadingRepository failedReadingRepository;
    private final IngestReceiptService ingestReceiptService;
    private final AnomalyDetector anomalyDetector;
    private final ApplicationEventPublisher eventPublisher;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * 한 batch 수신. 부분 실패(미지 채널·null 값)는 예외가 아니라 결과로 표현한다
     * — 예외로 롤백하면 failed_reading 적재까지 사라진다. 컨트롤러가 outcome 을 HTTP 로 매핑한다.
     */
    @Transactional
    public BatchIngestResult receive(BatchIngestRequest request) {
        Instant receivedAt = clock.instant();
        Instant observedAt = request.getObservedAt() != null ? request.getObservedAt() : receivedAt;
        String deviceCode = request.getDeviceCode();
        String eventId = request.getEventId();
        IngestReceipt receipt = null;

        if (eventId != null) {
            IngestReceiptService.Claim claim = ingestReceiptService.claim(
                    deviceCode, eventId, requestHash(request));
            if (claim.replay()) {
                return replay(claim.receipt());
            }
            if (claim.processing()) {
                throw new IllegalStateException("동일 ingest event가 아직 처리 중입니다: "
                        + deviceCode + "/" + eventId);
            }
            receipt = claim.receipt();
        }

        if (observedAt.isAfter(receivedAt.plus(MAX_FUTURE_SKEW))) {
            failedReadingRepository.save(FailedReading.builder()
                    .deviceCode(deviceCode).reason("FUTURE_OBSERVED_AT").build());
            log.warn("수신 거부(미래 관측 시각) - deviceCode: {}, observedAt: {}, receivedAt: {}",
                    deviceCode, observedAt, receivedAt);
            return completeReceipt(BatchIngestResult.futureObservedAt(
                    deviceCode, observedAt, receivedAt, eventId), eventId);
        }

        Device device = deviceRepository.findByCode(deviceCode).orElse(null);
        if (device == null) {
            // 조용히 버리지 않고 실패 요약 1행 적재(데이터 안 옴 신호 소스). → 404
            failedReadingRepository.save(FailedReading.builder()
                    .deviceCode(deviceCode).reason("DEVICE_NOT_FOUND").build());
            log.warn("수신 실패(장치 없음) - deviceCode: {}", deviceCode);
            return completeReceipt(
                    BatchIngestResult.deviceNotFound(deviceCode, receivedAt, eventId), eventId);
        }

        // 채널 Map 1회 로드 후 known/unknown 분할.
        Map<String, SensorChannel> channelsByCode = new HashMap<>();
        for (SensorChannel channel : sensorChannelRepository.findByDeviceId(device.getId())) {
            channelsByCode.put(channel.getCode(), channel);
        }

        List<RejectedReading> rejected = new ArrayList<>();
        List<FailedReading> failures = new ArrayList<>();
        Map<SensorChannel, Double> known = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : request.getMeasurements().entrySet()) {
            String code = entry.getKey();
            Double value = entry.getValue();
            SensorChannel channel = channelsByCode.get(code);
            if (channel == null) {
                rejected.add(new RejectedReading(code, "UNKNOWN_CHANNEL"));
                // deviceId 를 채워 freshness 원인진단(countByDeviceIdAndCreatedAtAfter)이 실패를 셀 수 있게 한다.
                failures.add(FailedReading.builder()
                        .deviceId(device.getId()).deviceCode(deviceCode).channelCode(code).value(value)
                        .reason("UNKNOWN_CHANNEL").build());
            } else if (value == null) {
                rejected.add(new RejectedReading(code, "NULL_VALUE"));
                failures.add(FailedReading.builder()
                        .deviceId(device.getId()).deviceCode(deviceCode).channelCode(code)
                        .reason("NULL_VALUE").build());
            } else {
                known.put(channel, value);
            }
        }
        // 거부된 판독은 한 번에 적재한다(개별 save round-trip 회피).
        if (!failures.isEmpty()) {
            failedReadingRepository.saveAll(failures);
        }

        if (known.isEmpty()) {
            // 전 채널 미지/무효 → batch 미생성, markSeen 안 함. → 422
            log.warn("수신 거부(known 채널 0) - deviceCode: {}, rejected: {}", deviceCode, rejected.size());
            return completeReceipt(BatchIngestResult.noKnownChannels(
                    device, deviceCode, observedAt, receivedAt, rejected, eventId), eventId);
        }

        List<Map.Entry<SensorChannel, Double>> orderedReadings = known.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(
                        SensorChannel::getId, Comparator.nullsLast(Long::compareTo))))
                .toList();

        // known ≥ 1 → batch + readings 저장.
        MeasurementBatch batch = measurementBatchRepository.save(MeasurementBatch.builder()
                .device(device).observedAt(observedAt).receivedAt(receivedAt)
                .sourceSeq(request.getSourceSeq()).receipt(receipt).build());

        for (Map.Entry<SensorChannel, Double> entry : orderedReadings) {
            SensorChannel channel = entry.getKey();
            Double value = entry.getValue();
            sensorReadingRepository.save(SensorReading.builder()
                    .batch(batch).channel(channel).value(value).build());
        }

        // 동시 batch는 channel PK 오름차순으로 같은 write-lock 순서를 사용한다.
        List<Long> orderedChannelIds = orderedReadings.stream()
                .map(entry -> entry.getKey().getId())
                .toList();
        List<ChannelStatus> lockedStatuses =
                channelStatusRepository.findAllByIdInForUpdateOrderByChannelId(orderedChannelIds);
        if (lockedStatuses.size() != orderedChannelIds.size()) {
            throw new IllegalStateException("channel_status가 없습니다. migration/create 경계를 확인하세요");
        }
        Map<Long, ChannelStatus> statusByChannelId = new HashMap<>();
        for (ChannelStatus status : lockedStatuses) {
            statusByChannelId.put(status.getChannelId(), status);
        }

        List<BatchSsePayload.Reading> sseReadings = new ArrayList<>(known.size());
        List<Alert> notificationEvents = new ArrayList<>();
        int stateAppliedCount = 0;
        for (Map.Entry<SensorChannel, Double> entry : orderedReadings) {
            SensorChannel channel = entry.getKey();
            // config update도 같은 status row를 잠근다. 잠금 대기 전에 읽은 threshold를
            // 그대로 쓰지 않도록 lock 획득 뒤 설정 엔티티를 DB 정본으로 새로고침한다.
            entityManager.refresh(channel);
            boolean anomaly = anomalyDetector.isAnomaly(channel, entry.getValue());
            ThresholdEvaluation evaluation = evaluateThreshold(
                    device, batch, channel, entry.getValue(), anomaly,
                    observedAt, receivedAt, statusByChannelId.get(channel.getId()));
            if (evaluation.stateApplied()) {
                stateAppliedCount++;
            }
            if (evaluation.notification() != null) {
                notificationEvents.add(evaluation.notification());
            }
            sseReadings.add(new BatchSsePayload.Reading(
                    channel.getId(), channel.getCode(), entry.getValue(),
                    anomaly, evaluation.stateApplied()));
        }

        markSeen(device, receivedAt);

        // 실시간 전송은 커밋 후(SseBroadcastListener). replay는 이 지점에 오지 않으므로 중복 SSE가 없다.
        eventPublisher.publishEvent(new SseBroadcastEvent("sensor-data", device.getId(),
                new BatchSsePayload(batch.getId(), device.getId(), observedAt, receivedAt, sseReadings)));
        for (Alert alert : notificationEvents) {
            eventPublisher.publishEvent(
                    new SseBroadcastEvent("alert", device.getId(), AlertResponse.from(alert)));
        }

        log.info("batch 수신 저장 - deviceCode: {}, saved: {}, rejected: {}",
                deviceCode, known.size(), rejected.size());
        return completeReceipt(BatchIngestResult.saved(
                batch, device, deviceCode, observedAt, receivedAt,
                known.size(), rejected, eventId, stateAppliedCount), eventId);
    }

    /**
     * reading 저장과 별개인 live-state 적용 경계. 역행 관측은 저장/SSE에는 남기되 여기서 즉시 제외한다.
     * episode는 감지 즉시 열고/전이/해제하며, 5분 제한은 Alert 통지에만 적용한다.
     */
    private ThresholdEvaluation evaluateThreshold(
            Device device, MeasurementBatch batch, SensorChannel channel, double value,
            boolean breach, Instant observedAt, Instant receivedAt, ChannelStatus status) {
        if (!status.shouldEvaluate(observedAt, batch.getId())) {
            log.debug("역행 reading 상태 미적용 - channel: {}, observedAt: {}, watermark: {}",
                    channel.getCode(), observedAt, status.getLastEvaluatedObservedAt());
            return ThresholdEvaluation.notApplied();
        }

        Optional<AlarmEpisode> openEpisode =
                alarmEpisodeRepository.findOpenThresholdForUpdate(channel.getId());
        Alert notification = null;

        if (breach) {
            AlertSeverity severity = anomalyDetector.isCritical(channel, value, CRITICAL_RATIO)
                    ? AlertSeverity.CRITICAL : AlertSeverity.WARNING;
            if (openEpisode.isEmpty()) {
                AlarmEpisode episode = alarmEpisodeRepository.save(AlarmEpisode.openThreshold(
                        device, channel, severity, receivedAt, thresholdSnapshot(device, channel)));
                status.markEvaluated(observedAt, batch.getId());
                if (notificationDue(episode, status, receivedAt)) {
                    notification = createNotification(
                            episode, device, channel, batch, value, severity,
                            AlertNotificationReason.INITIAL);
                    episode.markNotified(receivedAt);
                    status.enterAlarm(episode, receivedAt);
                } else {
                    // detection/episode는 즉시 열되 직전 episode 통지의 cooldown은 이어받는다.
                    status.mirror(episode, status.getLastAlertAt());
                }
                publishEpisode(AlarmEpisodeSsePayload.ChangeType.OPEN, episode);
            } else {
                AlarmEpisode episode = openEpisode.get();
                AlertSeverity previousSeverity = episode.getCurrentSeverity();
                episode.transitionSeverity(severity);
                status.markEvaluated(observedAt, batch.getId());

                AlertNotificationReason reason = null;
                if (previousSeverity == AlertSeverity.WARNING
                        && severity == AlertSeverity.CRITICAL) {
                    reason = AlertNotificationReason.SEVERITY_ESCALATION;
                } else if (notificationDue(episode, status, receivedAt)
                        && !acknowledgedAtCurrentSeverity(episode, severity)) {
                    reason = AlertNotificationReason.REMINDER;
                }

                if (reason != null) {
                    notification = createNotification(
                            episode, device, channel, batch, value, severity, reason);
                    episode.markNotified(receivedAt);
                    status.enterAlarm(episode, receivedAt);
                } else {
                    status.mirror(episode, status.getLastAlertAt());
                }
            }
        } else {
            status.markEvaluated(observedAt, batch.getId());
            if (openEpisode.isPresent()
                    && anomalyDetector.isReleased(channel, value, RELEASE_RATIO)) {
                openEpisode.get().resolve(receivedAt, AlarmResolutionReason.RECOVERED);
                publishEpisode(AlarmEpisodeSsePayload.ChangeType.RESOLVED, openEpisode.get());
                status.clearAlarm();
                log.info("threshold episode 해제 - channel: {}, value: {}",
                        channel.getCode(), value);
            } else if (openEpisode.isPresent()) {
                status.mirror(openEpisode.get(), status.getLastAlertAt());
            } else if (status.isInAlarm()) {
                // episode 정본이 없으면 stale mirror를 복구한다.
                status.clearAlarm();
            }
        }

        return new ThresholdEvaluation(true, notification);
    }

    private void publishEpisode(
            AlarmEpisodeSsePayload.ChangeType changeType, AlarmEpisode episode) {
        eventPublisher.publishEvent(new SseBroadcastEvent(
                "alarm-episode",
                episode.getDevice() == null ? null : episode.getDevice().getId(),
                new AlarmEpisodeSsePayload(
                        changeType, AlarmEpisodeResponse.from(episode, null))));
    }

    private Alert createNotification(
            AlarmEpisode episode, Device device, SensorChannel channel,
            MeasurementBatch batch, double value, AlertSeverity severity,
            AlertNotificationReason reason) {
        Double threshold = channel.getThresholdValue();
        Alert alert = alarmNotificationFactory.create(
                episode, device, channel, batch, value, threshold,
                String.format("[%s/%s] 임계값 %s! 현재값: %.1f, 임계값: %.1f",
                        device.getName(), channel.getCode(),
                        thresholdCondition(channel), value, threshold),
                severity, reason);
        Alert saved = alertRepository.save(alert);
        log.warn("Alert 통지 생성 - channel: {}, value: {}, severity: {}, reason: {}",
                channel.getCode(), value, severity, reason);
        return saved;
    }

    private boolean notificationDue(
            AlarmEpisode episode, ChannelStatus status, Instant receivedAt) {
        Instant lastNotifiedAt = episode.getLastNotifiedAt() != null
                ? episode.getLastNotifiedAt() : status.getLastAlertAt();
        return lastNotifiedAt == null
                || Duration.between(lastNotifiedAt, receivedAt)
                .compareTo(NOTIFICATION_COOLDOWN) >= 0;
    }

    private boolean acknowledgedAtCurrentSeverity(
            AlarmEpisode episode, AlertSeverity severity) {
        if (episode.getLastNotifiedAt() == null) {
            return false;
        }
        return alarmAcknowledgementRepository
                .findFirstByEpisodeIdOrderByCreatedAtDesc(episode.getId())
                .map(ack -> !ack.getCreatedAt().isBefore(episode.getLastNotifiedAt())
                        && severityRank(ack.getAckSeverity()) >= severityRank(severity))
                .orElse(false);
    }

    private static int severityRank(AlertSeverity severity) {
        return switch (severity) {
            case INFO -> 0;
            case WARNING -> 1;
            case CRITICAL -> 2;
        };
    }

    private Map<String, Object> thresholdSnapshot(Device device, SensorChannel channel) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("deviceCode", device.getCode());
        snapshot.put("deviceName", device.getName());
        snapshot.put("channelCode", channel.getCode());
        snapshot.put("zoneName", device.getZone() == null ? null : device.getZone().getName());
        snapshot.put("unit", channel.getUnit());
        snapshot.put("quantityKind", channel.getQuantityKind());
        snapshot.put("thresholdValue", channel.getThresholdValue());
        snapshot.put("thresholdDirection", channel.getThresholdDirection() == null
                ? null : channel.getThresholdDirection().name());
        return snapshot;
    }

    private String thresholdCondition(SensorChannel channel) {
        SensorChannel.ThresholdDirection direction = channel.getThresholdDirection() == null
                ? SensorChannel.ThresholdDirection.ABOVE : channel.getThresholdDirection();
        return switch (direction) {
            case BELOW -> "미만";
            case ABS_ABOVE -> "절댓값 초과";
            case ABOVE -> "초과";
        };
    }

    // 수신 하트비트는 Device(설정)가 아니라 DeviceStatus(텔레메트리)에 찍는다 — 설정 감사 오염 방지.
    private void markSeen(Device device, Instant now) {
        if (deviceStatusRepository.advanceLastSeenAt(device.getId(), now) != 1) {
            throw new IllegalStateException(
                    "device_status가 없습니다. migration/create 경계를 확인하세요: "
                            + device.getId());
        }
    }

    private String requestHash(BatchIngestRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("deviceCode", request.getDeviceCode());
        canonical.put("observedAt", request.getObservedAt());
        canonical.put("sourceSeq", request.getSourceSeq());
        Map<String, Double> measurements = new TreeMap<>(Comparator.nullsFirst(String::compareTo));
        measurements.putAll(request.getMeasurements());
        canonical.put("measurements", measurements);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("ingest 요청 hash를 계산할 수 없습니다", e);
        }
    }

    private BatchIngestResult replay(IngestReceipt receipt) {
        BatchIngestResponse response = objectMapper.convertValue(
                receipt.getResponseJson(), BatchIngestResponse.class);
        return new BatchIngestResult(
                BatchIngestResult.Outcome.valueOf(receipt.getOutcome().name()), response);
    }

    private BatchIngestResult completeReceipt(BatchIngestResult result, String eventId) {
        if (eventId == null) {
            return result;
        }
        Map<String, Object> responseJson = objectMapper.convertValue(
                result.response(), new TypeReference<>() {
                });
        ingestReceiptService.complete(
                result.response().deviceCode(), eventId,
                IngestOutcome.valueOf(result.outcome().name()),
                httpStatus(result.outcome()), responseJson, null);
        return result;
    }

    private int httpStatus(BatchIngestResult.Outcome outcome) {
        return switch (outcome) {
            case SAVED -> 200;
            case DEVICE_NOT_FOUND -> 404;
            case NO_KNOWN_CHANNELS, FUTURE_OBSERVED_AT -> 422;
        };
    }

    private record ThresholdEvaluation(boolean stateApplied, Alert notification) {
        private static ThresholdEvaluation notApplied() {
            return new ThresholdEvaluation(false, null);
        }
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> getReadingsByChannel(String employeeId, Long channelId, int limit) {
        User user = accessControlService.getUser(employeeId);
        SensorChannel channel = accessControlService.getChannel(channelId);
        accessControlService.assertCanAccessChannel(user, channel);
        int capped = Math.min(Math.max(limit, 1), MAX_RECENT);
        return sensorReadingRepository
                .findByChannelIdOrderByObservedAtDesc(channelId, PageRequest.of(0, capped))
                .stream()
                .map(reading -> ReadingResponse.from(
                        reading, anomalyDetector.isAnomaly(channel, reading.getValue())))
                .toList();
    }
}
