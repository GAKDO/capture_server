package com.gakdo.trace.capture.inspection.infra;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.inspection.domain.Defect;

public interface DefectRepository extends JpaRepository<Defect, Long> {

    /** 세트의 흠집(사진 순서·저장 순서). 사진을 함께 읽는다. */
    @Query("select d from Defect d join fetch d.photo p where p.captureSet = :set order by p.id, d.id")
    List<Defect> findBySet(CaptureSet set);

    boolean existsByPublicId(String publicId);

    /** 부스 탐지를 통째로 바꿀 때(11/9): 모델이 낸 흠집만 지우고 관리자가 직접 더한 흠집(HUMAN)은 남긴다. 매칭부터 지운 뒤 부른다. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from Defect d where d.source = com.gakdo.trace.capture.inspection.domain.DefectSource.AUTO"
            + " and d.photo in (select p from Photo p where p.captureSet = :set)")
    int deleteAutoBySet(CaptureSet set);

    /** 세트 흠집을 모두 지울 때. 매칭부터 지운 뒤 부른다. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("delete from Defect d where d.photo in (select p from Photo p where p.captureSet = :set)")
    int deleteBySet(CaptureSet set);
}
