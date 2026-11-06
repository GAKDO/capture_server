-- 성능 그래프 2(contracts #30, 11/6 C 요청): 부스가 보낸 촬영 시작 버튼→부스 판정 ms(선택). 이미 있는 세트는 null
ALTER TABLE capture.capture_set ADD COLUMN booth_total_ms integer CHECK (booth_total_ms IS NULL OR booth_total_ms >= 0);
