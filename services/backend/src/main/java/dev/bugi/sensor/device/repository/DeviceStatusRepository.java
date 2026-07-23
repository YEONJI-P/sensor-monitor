package dev.bugi.sensor.device.repository;

import dev.bugi.sensor.device.entity.DeviceStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface DeviceStatusRepository extends JpaRepository<DeviceStatus, Long> {

    // freshness 판정용. zone-코호트 비교를 트랜잭션 밖에서 하므로 device·zone 을 함께 로드한다.
    // JOIN 이라 수신 이력이 없는(status 행이 없는) 장치와 zone 없는 장치는 자연히 빠진다.
    @Query("""
            SELECT s FROM DeviceStatus s
            JOIN FETCH s.device d
            JOIN FETCH d.zone z
            JOIN FETCH z.factory
            WHERE d.expectedIntervalSeconds IS NOT NULL
            """)
    List<DeviceStatus> findMonitoredWithDeviceAndZone();

    @Query("""
            SELECT s FROM DeviceStatus s
            JOIN FETCH s.device d
            JOIN FETCH d.zone z
            JOIN FETCH z.factory
            """)
    List<DeviceStatus> findAllWithDeviceAndZone();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DeviceStatus s JOIN FETCH s.device WHERE s.deviceId = :deviceId")
    Optional<DeviceStatus> findByIdForUpdate(@Param("deviceId") Long deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM DeviceStatus s
            JOIN FETCH s.device d
            WHERE s.deviceId IN :deviceIds
            ORDER BY s.deviceId
            """)
    List<DeviceStatus> findAllByIdForUpdateOrderById(@Param("deviceIds") List<Long> deviceIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE DeviceStatus s
            SET s.lastSeenAt = CASE
                WHEN s.lastSeenAt IS NULL OR s.lastSeenAt < :receivedAt THEN :receivedAt
                ELSE s.lastSeenAt
            END
            WHERE s.deviceId = :deviceId
            """)
    int advanceLastSeenAt(@Param("deviceId") Long deviceId,
                          @Param("receivedAt") Instant receivedAt);
}
