package dev.bugi.sensor.device.repository;

import dev.bugi.sensor.device.entity.ChannelStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChannelStatusRepository extends JpaRepository<ChannelStatus, Long> {
    // PK 는 channel_id(@MapsId 공유 PK). findById(channelId) 로 상태를 찾는다.

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ChannelStatus s JOIN FETCH s.channel WHERE s.channelId = :channelId")
    Optional<ChannelStatus> findByIdForUpdate(@Param("channelId") Long channelId);

    /**
     * 한 batch의 hot rows를 항상 channel PK 오름차순으로 잠근다.
     * 서로 다른 map 순서로 들어온 동시 batch도 같은 lock order를 사용해 교착을 피한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM ChannelStatus s
            JOIN FETCH s.channel c
            WHERE s.channelId IN :channelIds
            ORDER BY s.channelId
            """)
    List<ChannelStatus> findAllByIdInForUpdateOrderByChannelId(
            @Param("channelIds") List<Long> channelIds);
}
