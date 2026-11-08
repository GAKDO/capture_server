package com.gakdo.trace.capture.captureset.infra;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.Quality;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.inspection.domain.DefectSource;

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

    /**
     * 사람 검수를 기다리다 멈춘 세트 ID: 시점의 가장 최근 세트 중 등급 전·검수 미완료이고 AI 후보가 있는 것(후보를 다 정하고 완료만 안 누른 세트도 포함).
     * 검수를 끈 채(REVIEW_REQUIRED=false) 다시 띄울 때 이 세트들에 등급을 매긴다(ReviewOffGrader).
     */
    @Query("""
            select distinct s.publicId from Defect d join d.photo p join p.captureSet s
            where s.status in :open and s.reviewCompletedAt is null
              and (s.quality is null or s.quality <> :unjudgeable)
              and d.source = :auto
              and s.id = (select max(s2.id) from CaptureSet s2 where s2.checkpointId = s.checkpointId)
            """)
    List<String> findStuckInReview(Collection<SetStatus> open, Quality unjudgeable, DefectSource auto);
}
