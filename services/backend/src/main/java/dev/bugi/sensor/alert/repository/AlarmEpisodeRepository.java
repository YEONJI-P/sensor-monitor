package dev.bugi.sensor.alert.repository;

import dev.bugi.sensor.alert.entity.AlarmEpisode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AlarmEpisodeRepository extends JpaRepository<AlarmEpisode, Long>,
        JpaSpecificationExecutor<AlarmEpisode> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM AlarmEpisode e WHERE e.id = :episodeId")
    Optional<AlarmEpisode> findByIdForUpdate(@Param("episodeId") Long episodeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT e FROM AlarmEpisode e
            WHERE e.alarmType = dev.bugi.sensor.alert.entity.AlarmType.THRESHOLD
              AND e.status = dev.bugi.sensor.alert.entity.AlarmEpisodeStatus.OPEN
              AND e.channel.id = :channelId
            """)
    Optional<AlarmEpisode> findOpenThresholdForUpdate(@Param("channelId") Long channelId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT e FROM AlarmEpisode e
            WHERE e.alarmType = dev.bugi.sensor.alert.entity.AlarmType.DEVICE_SILENCE
              AND e.status = dev.bugi.sensor.alert.entity.AlarmEpisodeStatus.OPEN
              AND e.device.id = :deviceId
            """)
    Optional<AlarmEpisode> findOpenDeviceSilenceForUpdate(@Param("deviceId") Long deviceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT e FROM AlarmEpisode e
            WHERE e.alarmType = dev.bugi.sensor.alert.entity.AlarmType.ZONE_SILENCE
              AND e.status = dev.bugi.sensor.alert.entity.AlarmEpisodeStatus.OPEN
              AND e.zone.id = :zoneId
            """)
    Optional<AlarmEpisode> findOpenZoneSilenceForUpdate(@Param("zoneId") Long zoneId);
}
