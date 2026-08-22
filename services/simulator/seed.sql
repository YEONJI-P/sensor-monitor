-- =============================================================================
-- IoT Sensor Platform — 초기 데이터 시드 스크립트
-- =============================================================================
-- 대상: factories, zones, users, zone_users, device, sensor_channel
-- 물리 Device ─ SensorChannel 모델: 물리 노드(device.code)와 그 아래 측정 채널
--   (sensor_channel.code) 로 나눈다. device/sensor_channel 내용은
--   services/backend/.../db/migration/V3/V4 의 데모
--   토폴로지와 정확히 일치해야 하며, services/simulator/simulator.py 의
--   DEVICE_PRESETS(deviceCode/채널code) 매핑도 이 값과 일치해야 한다.
-- 비밀번호: pgcrypto의 BCrypt(strength=10) — Spring BCryptPasswordEncoder 호환
--
-- 실행 전: 테이블이 생성된 상태(Spring Boot 기동 후)여야 하며, 중복 실행은
--   UNIQUE 제약 위반이 난다. 재실행은 하단 TRUNCATE 주석을 먼저 실행한다.
--
--   psql -U sensor_monitor -d sensor_monitor -f services/simulator/seed.sql
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- =============================================================================
-- 1. Factories
-- =============================================================================
INSERT INTO factories (name, description, created_at) VALUES
    ('엔진시험동', 'C-MAPSS 터보팬 엔진 시험 설비', NOW()),
    ('가공동',     'CNC 밀링 가공 설비',            NOW());

-- backend 운영 캘린더와 simulator CLI 운영시간은 서로 조회·동기화하지 않는 독립 설정이다.
-- 데모에서는 양쪽 기본값을 평일 08:00~18:00 Asia/Seoul로 맞춘다.
INSERT INTO factory_operating_calendar (
    factory_id, timezone_id, resume_grace_seconds, revision, created_at, updated_at
)
SELECT id, 'Asia/Seoul', 300, 0, NOW(), NOW() FROM factories;

INSERT INTO factory_weekly_interval (factory_id, iso_day, start_minute, end_minute)
SELECT f.id, day.iso_day, 480, 1080
FROM factories f
CROSS JOIN (VALUES (1), (2), (3), (4), (5)) AS day(iso_day);


-- =============================================================================
-- 2. Zones
-- =============================================================================
INSERT INTO zones (factory_id, name, description, created_at) VALUES
    ((SELECT id FROM factories WHERE name = '엔진시험동'), '엔진1구역', '엔진 unit 1 시험 라인', NOW()),
    ((SELECT id FROM factories WHERE name = '엔진시험동'), '엔진2구역', '엔진 unit 2 시험 라인', NOW()),
    ((SELECT id FROM factories WHERE name = '가공동'),     '밀링1구역', 'CNC 밀링 1호기 라인',   NOW());


-- =============================================================================
-- 3. Users  (password: BCrypt strength=10)
-- =============================================================================
-- DEMO는 전체 운영 데이터를 보는 공개 읽기 전용 계정이며 공장·구역에 배정하지 않는다.
-- 나머지 계정은 {공장}-{역할}로 직관화한다.
INSERT INTO users (employee_id, name, email, password, role, status, factory_id, created_at, updated_at) VALUES
    ('SYSTEM',    '시스템 관리자',   'system@sensor.local',    crypt('admin1234!', gen_salt('bf', 10)), 'SYSTEM_ADMIN',  'ACTIVE', NULL, NOW(), NOW()),
    ('DEMO',      '전체 열람 데모',   'demo@sensor.local',      crypt('demo1234!',  gen_salt('bf', 10)), 'SYSTEM_VIEWER', 'ACTIVE', NULL, NOW(), NOW()),
    ('ENG-ADMIN', '엔진동 관리자',   'eng-admin@sensor.local', crypt('admin1234!', gen_salt('bf', 10)), 'FACTORY_ADMIN', 'ACTIVE', (SELECT id FROM factories WHERE name = '엔진시험동'), NOW(), NOW()),
    ('CNC-ADMIN', '가공동 관리자',   'cnc-admin@sensor.local', crypt('admin1234!', gen_salt('bf', 10)), 'FACTORY_ADMIN', 'ACTIVE', (SELECT id FROM factories WHERE name = '가공동'),     NOW(), NOW()),
    ('ENG-OP',    '엔진동 설비담당', 'eng-op@sensor.local',    crypt('op1234!',    gen_salt('bf', 10)), 'MEMBER',        'ACTIVE', (SELECT id FROM factories WHERE name = '엔진시험동'), NOW(), NOW()),
    ('CNC-OP',    '가공동 설비담당', 'cnc-op@sensor.local',    crypt('op1234!',    gen_salt('bf', 10)), 'MEMBER',        'ACTIVE', (SELECT id FROM factories WHERE name = '가공동'),     NOW(), NOW()),
    ('ENG-VIEW',  '엔진동 열람',     'eng-view@sensor.local',  crypt('view1234!',  gen_salt('bf', 10)), 'VIEWER',        'ACTIVE', (SELECT id FROM factories WHERE name = '엔진시험동'), NOW(), NOW()),
    ('CNC-VIEW',  '가공동 열람',     'cnc-view@sensor.local',  crypt('view1234!',  gen_salt('bf', 10)), 'VIEWER',        'ACTIVE', (SELECT id FROM factories WHERE name = '가공동'),     NOW(), NOW());


-- =============================================================================
-- 4. Zone Users  (구역 스코프 접근제어 데모)
-- =============================================================================
INSERT INTO zone_users (zone_id, user_id, created_at) VALUES
    -- ENG-OP: 엔진1, 엔진2구역
    ((SELECT id FROM zones WHERE name = '엔진1구역'), (SELECT id FROM users WHERE employee_id = 'ENG-OP'), NOW()),
    ((SELECT id FROM zones WHERE name = '엔진2구역'), (SELECT id FROM users WHERE employee_id = 'ENG-OP'), NOW()),
    -- CNC-OP: 밀링1구역
    ((SELECT id FROM zones WHERE name = '밀링1구역'), (SELECT id FROM users WHERE employee_id = 'CNC-OP'), NOW()),
    -- ENG-VIEW: 엔진1, 엔진2구역 (읽기 전용)
    ((SELECT id FROM zones WHERE name = '엔진1구역'), (SELECT id FROM users WHERE employee_id = 'ENG-VIEW'), NOW()),
    ((SELECT id FROM zones WHERE name = '엔진2구역'), (SELECT id FROM users WHERE employee_id = 'ENG-VIEW'), NOW()),
    -- CNC-VIEW: 밀링1구역 (읽기 전용)
    ((SELECT id FROM zones WHERE name = '밀링1구역'), (SELECT id FROM users WHERE employee_id = 'CNC-VIEW'), NOW());


-- =============================================================================
-- 5. Devices  (물리 노드. 설정만 가진다 — 측정 종류·임계값은 채널 경계로 이동)
--    code 는 simulator.py DEVICE_PRESETS 의 deviceCode 와 일치해야 한다.
-- =============================================================================
INSERT INTO device (
    zone_id, code, name, location, expected_interval_seconds, created_at, updated_at
) VALUES
    ((SELECT id FROM zones WHERE name = '엔진1구역'), 'CMAPSS-U1', '엔진 유닛1', 'C-MAPSS unit1', 10, NOW(), NOW()),
    ((SELECT id FROM zones WHERE name = '엔진2구역'), 'CMAPSS-U2', '엔진 유닛2', 'C-MAPSS unit2', 10, NOW(), NOW()),
    ((SELECT id FROM zones WHERE name = '밀링1구역'), 'CNC-EXP01', 'CNC 1호기',  'CNC exp01',     10, NOW(), NOW());


-- =============================================================================
-- 6. Sensor Channels  (물리 device 아래 측정 채널. 임계값·임계 방향은 채널이 가진다)
--    C-MAPSS 신규 threshold는 FD001 각 unit의 초기 20%를 합친 건강 분포에서
--    열화 방향 단측 99.5 percentile(하락은 0.5 percentile)을 반올림했다:
--    s2 643.4 ABOVE, s7 552.4 BELOW, s15 8.485 ABOVE, s21 23.165 BELOW.
--    기존 s4 1416 ABOVE, s11 47.8 ABOVE 계약은 보존한다.
--    CNC ABS_ABOVE threshold는 experiment01 abs(value) 99.5 percentile 반올림값:
--    X/Y/Z acceleration 800/500/1000, X current 14.
--    code 는 simulator.py DEVICE_PRESETS 의 채널 code 와 일치해야 한다.
-- =============================================================================
INSERT INTO sensor_channel (
    device_id, code, unit, quantity_kind, threshold_value, threshold_direction, created_at, updated_at
) VALUES
    ((SELECT id FROM device WHERE code = 'CMAPSS-U1'), 's2',                   '°R',    'temperature',    643.4, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U1'), 's4',                   '°R',    'temperature',   1416.0, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U1'), 's7',                   'psia',  'pressure',       552.4, 'BELOW', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U1'), 's11',                  'psia',  'pressure',        47.8, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U1'), 's15',                  NULL,    'ratio',            8.485, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U1'), 's21',                  'lbm/s', 'coolant_flow',   23.165, 'BELOW', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U2'), 's2',                   '°R',    'temperature',    643.4, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U2'), 's4',                   '°R',    'temperature',   1416.0, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U2'), 's7',                   'psia',  'pressure',       552.4, 'BELOW', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U2'), 's11',                  'psia',  'pressure',        47.8, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U2'), 's15',                  NULL,    'ratio',            8.485, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CMAPSS-U2'), 's21',                  'lbm/s', 'coolant_flow',   23.165, 'BELOW', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'S1_OutputPower',       'kW',    'power',            0.25, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'S1_CurrentFeedback',   'A',     'current',         30.0, 'ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'X1_ActualAcceleration','mm/s²', 'acceleration',   800.0, 'ABS_ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'Y1_ActualAcceleration','mm/s²', 'acceleration',   500.0, 'ABS_ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'Z1_ActualAcceleration','mm/s²', 'acceleration',  1000.0, 'ABS_ABOVE', NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'X1_CurrentFeedback',   'A',     'current',         14.0, 'ABS_ABOVE', NOW(), NOW()),
    -- 표시 전용: 데이터셋 메타데이터로 확정하지 못한 unit과 threshold를 만들지 않는다.
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'S1_ActualVelocity',    NULL,    'velocity',        NULL, NULL, NOW(), NOW()),
    ((SELECT id FROM device WHERE code = 'CNC-EXP01'), 'M1_CURRENT_FEEDRATE',  NULL,    'feedrate',        NULL, NULL, NOW(), NOW());


-- =============================================================================
-- 전체 초기화 (재실행 필요 시 먼저 실행 후 위 INSERT 재실행)
-- =============================================================================
-- TRUNCATE alert, failed_reading, sensor_reading, measurement_batch, channel_status,
--   sensor_channel, zone_users, device, zones, users, factories RESTART IDENTITY CASCADE;
