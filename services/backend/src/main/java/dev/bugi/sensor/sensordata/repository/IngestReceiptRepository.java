package dev.bugi.sensor.sensordata.repository;

import dev.bugi.sensor.sensordata.entity.IngestReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IngestReceiptRepository extends JpaRepository<IngestReceipt, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO ingest_receipt (
                device_code, event_id, request_hash, created_at
            ) VALUES (
                :deviceCode, :eventId, :requestHash, :createdAt
            )
            ON CONFLICT (device_code, event_id) DO NOTHING
            """, nativeQuery = true)
    int reserve(@Param("deviceCode") String deviceCode,
                @Param("eventId") String eventId,
                @Param("requestHash") String requestHash,
                @Param("createdAt") Instant createdAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r FROM IngestReceipt r
            WHERE r.deviceCode = :deviceCode AND r.eventId = :eventId
            """)
    Optional<IngestReceipt> findByDeviceCodeAndEventIdForUpdate(
            @Param("deviceCode") String deviceCode,
            @Param("eventId") String eventId);

    Optional<IngestReceipt> findByDeviceCodeAndEventId(String deviceCode, String eventId);
}
