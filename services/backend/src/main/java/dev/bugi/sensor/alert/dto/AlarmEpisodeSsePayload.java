package dev.bugi.sensor.alert.dto;

import java.util.Objects;

public record AlarmEpisodeSsePayload(
        ChangeType changeType,
        AlarmEpisodeResponse episode
) {
    public AlarmEpisodeSsePayload {
        Objects.requireNonNull(changeType, "changeType");
        Objects.requireNonNull(episode, "episode");
    }

    public enum ChangeType {
        OPEN,
        ACK,
        RESOLVED,
        ENRICHED
    }
}
