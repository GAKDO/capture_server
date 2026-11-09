-- 정합 실패 사유(11/9 민서 요청): trace.inspect.completed failedPhotos[].reason을 사진마다 남겨 판정 불가 면 사유에 보인다.
-- 이미 있는 사진은 null(사유 모름)
ALTER TABLE capture.photo ADD COLUMN align_failure varchar(500);
