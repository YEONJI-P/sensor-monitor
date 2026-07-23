-- explain 보강 worker의 짧은 claim과 HTTP 밖 lease를 추적한다.
ALTER TABLE alert
    ADD COLUMN enrichment_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN enrichment_claimed_at timestamptz(6),
    ADD COLUMN enrichment_lease_token uuid,
    ADD COLUMN enrichment_next_attempt_at timestamptz(6),
    ADD COLUMN enrichment_completed_at timestamptz(6),
    ADD COLUMN enrichment_error varchar(1000),
    ADD CONSTRAINT ck_alert_enrichment_attempts CHECK (enrichment_attempts >= 0),
    ADD CONSTRAINT ck_alert_enrichment_lease CHECK (
        (enrichment_claimed_at IS NULL AND enrichment_lease_token IS NULL)
        OR
        (enrichment_claimed_at IS NOT NULL AND enrichment_lease_token IS NOT NULL)
    );

-- 이미 근거가 있는 기존 alert는 보강 완료로 간주한다.
UPDATE alert
SET enrichment_completed_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
WHERE evidence IS NOT NULL;

CREATE INDEX idx_alert_enrichment_claim
    ON alert (enrichment_next_attempt_at, created_at)
    WHERE enrichment_completed_at IS NULL AND evidence IS NULL;
