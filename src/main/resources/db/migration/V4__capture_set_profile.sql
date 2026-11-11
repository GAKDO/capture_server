-- 세트 범위(contracts capture.profile, 11/3 MVP 재조정). QUICK은 부스 빠른 검사(위·아래 2면)로 정합 없이 부스 탐지만으로 등급을 매긴다.
-- 기존 세트는 모두 FULL.
ALTER TABLE capture.capture_set ADD COLUMN profile varchar(8) NOT NULL DEFAULT 'FULL';
ALTER TABLE capture.capture_set ADD CONSTRAINT capture_set_profile_check CHECK (profile IN ('FULL', 'QUICK'));
