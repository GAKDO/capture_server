package com.gakdo.trace.capture.inspection.infra;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.inspection.domain.GradeResult;

public interface GradeResultRepository extends JpaRepository<GradeResult, Long> {

    Optional<GradeResult> findByCheckpointId(String checkpointId);

    /** 같은 렌탈에서 등급이 난 바로 앞 시점(다른 시점 중 가장 최근). 출고면 없다. */
    Optional<GradeResult> findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(String rentalId,
            String checkpointId);

    /** 같은 렌탈에서 이 시점보다 먼저(첫 등급 시각 기준) 등급이 난 다른 시점 수. 시점 종류를 정할 때 쓴다(CheckpointKind.ofPosition). */
    long countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(String rentalId, String checkpointId,
            OffsetDateTime createdAt);

    /**
     * 전체 세트(FULL) 비교용 바로 앞 시점: 같은 범위(profile) 세트에서 등급이 난 것만 고른다. 빠른 검사(QUICK) 결과는 표준 좌표 흠집이
     * 없어 앞 시점으로 쓰면 같은 흠집이 모두 NEW로 잡힌다.
     */
    Optional<GradeResult> findFirstByCaptureSetRentalIdAndCaptureSetProfileAndCheckpointIdNotOrderByUpdatedAtDesc(
            String rentalId, CaptureProfile profile, String checkpointId);
}
