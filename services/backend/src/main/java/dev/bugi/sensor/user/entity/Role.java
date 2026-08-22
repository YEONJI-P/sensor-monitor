package dev.bugi.sensor.user.entity;

public enum Role {
    SYSTEM_ADMIN,   // 전체 시스템 관리 (모든 공장·장치)
    SYSTEM_VIEWER,  // 전체 시스템 읽기 전용
    FACTORY_ADMIN,  // 공장 단위 관리 (소속 공장의 구역·사용자)
    MEMBER,         // 소속 구역 읽기+쓰기 (장치 관리·데이터 입력·분석)
    VIEWER;         // 소속 구역 읽기 전용

    public boolean hasGlobalReadScope() {
        return this == SYSTEM_ADMIN || this == SYSTEM_VIEWER;
    }

    public boolean isReadOnly() {
        return this == SYSTEM_VIEWER || this == VIEWER;
    }
}
