package dev.bugi.sensor.alert.entity;

import dev.bugi.sensor.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "alarm_acknowledgement")
public class AlarmAcknowledgement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "episode_id", nullable = false)
    private AlarmEpisode episode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity ackSeverity;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public AlarmAcknowledgement(AlarmEpisode episode, User user,
                                AlertSeverity ackSeverity, Instant createdAt) {
        this.episode = Objects.requireNonNull(episode, "episode");
        this.user = Objects.requireNonNull(user, "user");
        this.ackSeverity = Objects.requireNonNull(ackSeverity, "ackSeverity");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
