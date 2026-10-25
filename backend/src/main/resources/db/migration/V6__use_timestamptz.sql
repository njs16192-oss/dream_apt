-- ADR 006 3-⑧: 시각 컬럼을 TIMESTAMPTZ로 통일한다
-- TIMESTAMP는 세션 시간대 기준 현지 시각을 시간대 없이 저장한다. pgjdbc는 세션 시간대를 JVM 시간대로 잡으므로
-- 개발 PC(KST)와 k8s(UTC)에서 쓴 값이 섞인다. 기존 값은 개발 PC에서 쓴 서울 시각이라 Asia/Seoul로 해석해 변환한다

ALTER TABLE apt_complex
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Seoul';

ALTER TABLE apt_trade
    ALTER COLUMN created_at    TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN updated_at    TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'Asia/Seoul',
    ALTER COLUMN missing_since TYPE TIMESTAMPTZ USING missing_since AT TIME ZONE 'Asia/Seoul';
