package dev.bugi.sensor.alert.repository;

import dev.bugi.sensor.alert.entity.AlarmAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlarmAcknowledgementRepository extends JpaRepository<AlarmAcknowledgement, Long> {
    List<AlarmAcknowledgement> findByEpisodeIdOrderByCreatedAtDesc(Long episodeId);

    Optional<AlarmAcknowledgement> findFirstByEpisodeIdOrderByCreatedAtDesc(Long episodeId);

}
