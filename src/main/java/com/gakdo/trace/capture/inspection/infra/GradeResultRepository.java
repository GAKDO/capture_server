package com.gakdo.trace.capture.inspection.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.capture.inspection.domain.GradeResult;

public interface GradeResultRepository extends JpaRepository<GradeResult, Long> {

    Optional<GradeResult> findByCheckpointId(String checkpointId);

    /** 같은 렌탈에서 등급이 난 바로 앞 시점(다른 시점 중 가장 최근). 출고면 없다. */
    Optional<GradeResult> findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(String rentalId,
            String checkpointId);
}
