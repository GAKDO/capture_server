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

    /** 시점의 가장 최근 세트 ID만. 잠그기 전에 엔티티를 읽으면 잠근 뒤에도 옛 상태가 남아 ID만 먼저 찾는다(검수). */
    @Query("select s.publicId from CaptureSet s where s.checkpointId = :checkpointId order by s.id desc limit 1")
    Optional<String> findLatestPublicIdByCheckpointId(String checkpointId);

    /** 완료는 세트 행을 잠가 같은 세트의 완료 요청이 동시에 와도 한 번만 처리한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CaptureSet s where s.publicId = :publicId")
    Optional<CaptureSet> findForUpdate(String publicId);
}
