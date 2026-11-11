package com.gakdo.trace.capture.inspection.infra;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.inspection.domain.DefectMatchRecord;

public interface DefectMatchRepository extends JpaRepository<DefectMatchRecord, Long> {

    @Query("select m from DefectMatchRecord m join fetch m.defect d left join fetch m.prevDefect "
            + "where d.photo.captureSet = :set")
    List<DefectMatchRecord> findBySet(CaptureSet set);

    @Modifying(flushAutomatically = true)
    @Query("delete from DefectMatchRecord m where m.defect in "
            + "(select d from Defect d where d.photo.captureSet = :set)")
    int deleteBySet(CaptureSet set);
}
