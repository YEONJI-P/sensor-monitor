package dev.bugi.sensor.alert.entity;

/**
 * 같은 enum을 episode와 Alert가 공유해 탐지 종류가 서로 어긋나지 않게 한다.
 */
public enum AlarmType {
    THRESHOLD,
    DEVICE_SILENCE,
    ZONE_SILENCE
}
