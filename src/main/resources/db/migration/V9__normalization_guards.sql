-- 정규화(11/20 ERD 점검): 같은 사실을 두 칸에 두는 곳에 DB 확인을 더한다. 칸·코드는 그대로라 이전 버전 코드에서도 돈다.
-- 이미 있는 행 때문에 서버가 못 뜨는 일이 없도록 NOT VALID로 더한 뒤 확인을 시도하고, 어긋난 행이 있으면 알림만 남긴다
-- (새로 넣거나 고치는 행은 늘 확인된다).

-- 1) grade_result.checkpoint_id와 capture_set_id: 세트가 시점을 정한다(capture_set.checkpoint_id).
--    "시점당 등급 하나"(UNIQUE checkpoint_id) 때문에 checkpoint_id는 빼지 못하므로, 두 칸이 같은 세트·시점을 가리키는지를
--    합성 FK로 묶는다. capture_set_id가 NULL인 옛 행(V2 전)은 확인하지 않는다(MATCH SIMPLE).
ALTER TABLE capture.capture_set ADD CONSTRAINT uq_capture_set_id_checkpoint UNIQUE (id, checkpoint_id);
ALTER TABLE inspection.grade_result ADD CONSTRAINT fk_grade_result_set_checkpoint
  FOREIGN KEY (capture_set_id, checkpoint_id) REFERENCES capture.capture_set(id, checkpoint_id) NOT VALID;

-- 2) defect_match.status와 prev_defect_id: EXISTING이면 짝지은 앞 흠집이 있고 NEW면 없다(ViewComparator).
--    status는 계약 값(codes.yaml inspection.match_status)이라 두고, 두 칸이 어긋나지 못하게 한다.
ALTER TABLE inspection.defect_match ADD CONSTRAINT ck_defect_match_prev
  CHECK ((status = 'EXISTING') = (prev_defect_id IS NOT NULL)) NOT VALID;

DO $$
BEGIN
  BEGIN
    ALTER TABLE inspection.grade_result VALIDATE CONSTRAINT fk_grade_result_set_checkpoint;
  EXCEPTION WHEN foreign_key_violation THEN
    RAISE NOTICE 'grade_result에 세트와 시점이 어긋난 행이 있습니다. 새 행만 확인합니다.';
  END;
  BEGIN
    ALTER TABLE inspection.defect_match VALIDATE CONSTRAINT ck_defect_match_prev;
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'defect_match에 status와 prev_defect_id가 어긋난 행이 있습니다. 새 행만 확인합니다.';
  END;
END $$;
