-- 성능 그래프(contracts GET /api/v1/captures/benchmarks, 11/6 C 요청). 이미 있는 세트는 비워 둔다(null).
-- completed_at: 촬영 완료 요청을 받은 시각. 완료→등급 처리 시간의 시작점
-- detection_model_version·inference_ms: 부스가 탐지 결과와 함께 보낸 모델 버전과 탐지 시간(ms, 선택)
ALTER TABLE capture.capture_set ADD COLUMN completed_at timestamptz;
ALTER TABLE capture.capture_set ADD COLUMN detection_model_version varchar(64);
ALTER TABLE capture.capture_set ADD COLUMN inference_ms integer CHECK (inference_ms IS NULL OR inference_ms >= 0);
CREATE INDEX ix_capture_set_created ON capture.capture_set(created_at DESC);
