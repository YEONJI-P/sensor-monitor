# IoT Sensor Platform 작업 지침

구조, API, 포트, 실행 방법의 현재 정본은 `README.md`와 실제 설정이다. 이 파일에는 반복해서 틀리기 쉬운 행동 규칙만 둔다.

## 작업 위치와 명령

- Gradle 명령은 `services/backend/`에서 실행한다.
- Spring 실행: `./gradlew bootRun`
- Spring 검증: `./gradlew test` 또는 변경 범위에 맞는 `--tests` 선택
- 독립 풀 데모: 저장소 루트에서 `docker compose up --build` (원커맨드는 `make demo`).
- 홈서버/prod 배포는 이 저장소가 아니라 bugi-server-infra가 소유한다(compose·nginx·이미지 pin·ingest 키·실행 인자·배포 자동화). prod 계약은 bugi-server-infra `CONTRACT.md`를 따른다.
- simulator는 replay 일회성 프로파일과 synthetic live 프로파일을 구분한다. 정확한 인자는 `README.md`를 확인한다.
- H2 context smoke test는 Spring 설정과 bean 부팅만 확인한다. repository 쿼리, 제약, PostgreSQL 타입은 Docker가 필요한 Testcontainers 테스트로 검증한다.

## 설정과 비밀값

- `.env` 원문은 읽지 않는다. 서비스별 `.env.example`, `application.yml`, `docker-compose.yml`에서 키와 계약만 확인한다.
- 서비스 설정 키는 소비하는 서비스의 `.env.example`에만 둔다. 컨테이너 간 주소와 데모 토폴로지는 compose 설정에 둔다.
- 포트와 호스트명을 문서 기억으로 정하지 말고 실행 모드의 실제 설정을 확인한다. 로컬 단독 실행과 컨테이너 데모의 포트를 섞지 않는다.
- `JWT_SECRET`과 provider API key는 환경변수로만 주입한다.

## Health와 외부 연동

- Spring health는 `/actuator/health`, explain health는 `/health`를 사용한다.
- health는 무인증 `HTTP 200`과 `{"status":"UP"}`만 공개하고 내부 상세를 노출하지 않는다.
- bugi-server-infra 편입은 이 저장소의 내부 구현과 별개다. 이미지, 라우팅, 내부 감시 URL 계약은 bugi-server-infra의 `CONTRACT.md`에서 관리한다.

## 시각 처리

- DB 시각 컬럼은 `timestamptz`, Java 시각 필드는 `Instant`를 기본으로 쓴다. 시간대가 없는 `LocalDateTime`을 새로 쓰지 않는다.
- 현재 시각은 직접 호출하지 않고 주입한 `Clock`에서 얻는다.
- API는 ISO 8601 UTC 문자열을 반환하고 사용자 시간대 변환은 프론트가 담당한다.
- DB의 `timezone` GUC와 컨테이너 `TZ`는 바꾸지 않고 UTC 기본값을 유지한다.
- 서버가 최종 문서를 렌더하거나 지역 자정 기준 집계가 필요할 때만 명시적으로 zone을 변환한다.

## Device 상태 경계

- `Device`에는 이름, 타입, 위치, 임계값, 기대 주기 같은 설정만 둔다.
- `lastSeenAt`, `inAlarm`, `lastAlertAt`과 새 runtime 또는 telemetry 값은 `DeviceStatus`에 둔다.
- `DeviceStatus` 갱신 때문에 `Device.updatedAt`이 바뀌게 만들지 않는다.
- 이 경계를 바꿀 필요가 있으면 코드만 수정하지 말고 `README.md`의 ERD와 설계 메모도 함께 검토한다.

## 변경 검증

- 인증과 권한 변경은 역할별 허용 및 거부 경로를 검증한다.
- DB mapping, native query, migration 변경은 PostgreSQL 기반 테스트로 확인한다.
- HTTP 호출은 transaction 밖에서 수행한다는 기존 경계를 유지한다.
- 현재 진행 상태, branch, push 여부를 이 파일에 기록하지 않는다.
