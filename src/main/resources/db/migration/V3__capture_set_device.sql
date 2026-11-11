-- 찍은 기기의 기종·치수(contracts CaptureDevice, 11/2 민서 결정). 세트 생성 요청의 device를 그대로 두었다가
-- trace.capture.uploaded의 device로 싣는다. inspect-worker가 기종마다 표준 좌표 크기를 정한다. 없으면 NULL(worker 기본 기종).
ALTER TABLE capture.capture_set ADD COLUMN device jsonb;
