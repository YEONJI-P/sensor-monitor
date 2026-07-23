package dev.bugi.sensor.alert.entity;

public enum AlarmResolutionReason {
    RECOVERED,
    CONFIG_CHANGED,
    PLANNED_OFFLINE,
    RESUME_GRACE,
    RECLASSIFIED,
    MONITORING_DISABLED
}
