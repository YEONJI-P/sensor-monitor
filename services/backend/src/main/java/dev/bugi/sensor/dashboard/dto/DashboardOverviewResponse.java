package dev.bugi.sensor.dashboard.dto;

import dev.bugi.sensor.device.entity.SensorChannel;

import java.time.Instant;
import java.util.List;

public record DashboardOverviewResponse(
        Instant generatedAt,
        List<FactoryOverview> factories
) {
    public record FactoryOverview(Long id, String name, List<ZoneOverview> zones) {
    }

    public record ZoneOverview(Long id, String name, List<DeviceOverview> devices) {
    }

    public record DeviceOverview(
            Long id,
            String code,
            String name,
            String location,
            Integer expectedIntervalSeconds,
            Instant lastSeenAt,
            Freshness freshness,
            int currentAlarmCount,
            List<ChannelOverview> channels,
            long activeEpisodeCount
    ) {
    }

    public record ChannelOverview(
            Long id,
            String code,
            String unit,
            String quantityKind,
            Double latestValue,
            Instant latestObservedAt,
            Instant latestReceivedAt,
            boolean anomaly,
            boolean inAlarm,
            Instant lastAlertAt,
            Double thresholdValue,
            SensorChannel.ThresholdDirection thresholdDirection,
            Long activeEpisodeId
    ) {
    }

    public enum Freshness {
        NOT_MONITORED,
        PLANNED_OFFLINE,
        RESUMING,
        NEVER_SEEN,
        ONLINE,
        STALE
    }
}
