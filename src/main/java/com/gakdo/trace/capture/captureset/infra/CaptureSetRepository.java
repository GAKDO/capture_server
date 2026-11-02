package com.gakdo.trace.capture.captureset.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;

import jakarta.persistence.LockModeType;

public interface CaptureSetRepository extends JpaRepository<CaptureSet, Long> {

    Optional<CaptureSet> findByPublicId(String publicId);

    /** 시점의 가장 최근 세트(검사 결과 조회, CA2). */
    Optional<CaptureSet> findFirstByCheckpointIdOrderByIdDesc(String checkpointId);

    /** 완료는 세트 행을 잠가 같은 세트의 완료 요청이 동시에 와도 한 번만 처리한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CaptureSet s where s.publicId = :publicId")
    Optional<CaptureSet> findForUpdate(String publicId);
}
