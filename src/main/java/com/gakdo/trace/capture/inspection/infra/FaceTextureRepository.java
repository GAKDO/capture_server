package com.gakdo.trace.capture.inspection.infra;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.inspection.domain.FaceTexture;

public interface FaceTextureRepository extends JpaRepository<FaceTexture, Long> {

    List<FaceTexture> findByCaptureSet(CaptureSet set);
}
