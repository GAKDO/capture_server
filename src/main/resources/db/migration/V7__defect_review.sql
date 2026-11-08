-- 사람 검수(contracts 1.3, 11/8 민서 결정): AI가 넓게 잡은 흠집 후보를 관리자가 확정·기각한 뒤에 등급을 매긴다.
-- 이미 등급이 난 세트의 흠집은 검수 없이 쓴 것이라 CONFIRMED로 두고, 아직 등급 전인 세트의 흠집만 PENDING으로 돌린다.
ALTER TABLE inspection.defect ADD COLUMN review_status varchar(10) NOT NULL DEFAULT 'CONFIRMED'
  CHECK (review_status IN ('PENDING', 'CONFIRMED', 'DISMISSED'));
UPDATE inspection.defect d SET review_status = 'PENDING'
  FROM capture.photo p JOIN capture.capture_set cs ON cs.id = p.capture_set_id
  WHERE p.id = d.photo_id AND cs.status <> 'GRADED' AND d.source = 'AUTO';
ALTER TABLE inspection.defect ALTER COLUMN review_status SET DEFAULT 'PENDING';
ALTER TABLE inspection.defect ADD COLUMN dismiss_reason varchar(12)
  CHECK (dismiss_reason IS NULL OR dismiss_reason IN ('DUST', 'TAPE', 'REFLECTION', 'PART', 'OTHER'));
ALTER TABLE inspection.defect ADD COLUMN reviewed_by varchar(64);
ALTER TABLE inspection.defect ADD COLUMN reviewed_at timestamptz;
-- 기각에만 사유가 있다
ALTER TABLE inspection.defect ADD CONSTRAINT ck_defect_dismiss_reason
  CHECK ((review_status = 'DISMISSED') = (dismiss_reason IS NOT NULL));

-- 세트의 검수 완료(등급 발행 허락). completion_key는 Idempotency-Key
ALTER TABLE capture.capture_set ADD COLUMN review_completed_at timestamptz;
ALTER TABLE capture.capture_set ADD COLUMN review_completed_by varchar(64);
ALTER TABLE capture.capture_set ADD COLUMN review_completion_key varchar(128);
CREATE INDEX ix_capture_set_review_open ON capture.capture_set(completed_at)
  WHERE review_completed_at IS NULL AND status <> 'GRADED';
