package dev.bugi.sensor.factory.repository;

import dev.bugi.sensor.factory.entity.Zone;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ZoneRepository extends JpaRepository<Zone, Long> {
    List<Zone> findAllByFactoryId(Long factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT z FROM Zone z WHERE z.id = :zoneId")
    Optional<Zone> findByIdForUpdate(@Param("zoneId") Long zoneId);
}
