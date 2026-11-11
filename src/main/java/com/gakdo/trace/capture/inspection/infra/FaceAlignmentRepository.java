package com.gakdo.trace.capture.inspection.infra;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.inspection.domain.FaceAlignment;

public interface FaceAlignmentRepository extends JpaRepository<FaceAlignment, Long> {

    @Query("select a from FaceAlignment a join fetch a.photo p where p.captureSet = :set")
    List<FaceAlignment> findBySet(CaptureSet set);
}
