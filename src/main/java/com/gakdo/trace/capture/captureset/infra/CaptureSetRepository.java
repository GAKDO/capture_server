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

    /** 같은 시점의 세트 만들기를 트랜잭션 동안 직렬화한다(PostgreSQL advisory lock). 짝 확인과 저장 사이에 다른 렌탈이 끼지 않게. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext(:checkpointId))", nativeQuery = true)
    Integer lockCheckpoint(@org.springframework.data.repository.query.Param("checkpointId") String checkpointId);

    /** 이 시점에 다른 렌탈로 만든 세트가 있는지(시점과 렌탈 짝 확인). */
    boolean existsByCheckpointIdAndRentalIdNot(String checkpointId, String rentalId);

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

    /**
     * AI 후보 없이 검수를 기다리는 세트 ID(11/9 REVIEW_HOLD_EMPTY): 시점의 가장 최근 세트 중 촬영 완료·등급 전·검수 미완료이고 AUTO 흠집이
     * 하나도 없는 것. 이 설정을 끄고 다시 띄울 때 등급을 매겨 대기 목록에서 사라진 채 남지 않게 한다(ReviewOffGrader).
     */
    @Query("""
            select s.publicId from CaptureSet s
            where s.status in :open and s.reviewCompletedAt is null and s.completedAt is not null
              and (s.quality is null or s.quality <> :unjudgeable)
              and s.id = (select max(s2.id) from CaptureSet s2 where s2.checkpointId = s.checkpointId)
              and not exists (select d.id from Defect d where d.photo.captureSet = s and d.source = :auto)
            """)
    List<String> findWaitingWithoutCandidates(Collection<SetStatus> open, Quality unjudgeable, DefectSource auto);
}
