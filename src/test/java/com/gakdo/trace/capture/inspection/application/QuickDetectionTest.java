package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.application.CaptureSetService.PhotoRef;
import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;

/** 빠른 검사 탐지 요청은 TOP·BOT 사진 전부의 분석 결과여야 한다(contracts #20 리뷰). Docker 없이 돈다. */
class QuickDetectionTest {

    private static final String SHA = "c".repeat(64);
    private static final Caller BOOTH = new Caller("BOOTH", "booth-1", null, null);

    private CaptureSetRepository sets;
    private DefectRepository defects;
    private GradingService grading;
    private DetectionService service;
    private CaptureSet set;

    @BeforeEach
    void setUp() {
        sets = mock(CaptureSetRepository.class);
        defects = mock(DefectRepository.class);
        grading = mock(GradingService.class);
        service = new DetectionService(sets, defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), grading);
        set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH, "booth-1", null,
                CaptureProfile.QUICK, specs());
        when(sets.findForUpdate(any())).thenReturn(Optional.of(set));
    }

    @Test
    @DisplayName("빠른 검사 탐지 요청에 analyzedPhotos가 없으면 400(analyzedPhotos)이고 저장·등급을 하지 않는다")
    void analyzedRequired() {
        assertThatThrownBy(() -> service.save(BOOTH, set.getPublicId(), "v", List.of(), null))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).extracting(DomainException.FieldError::field)
                            .containsExactly("analyzedPhotos");
                });
        verify(defects, never()).deleteBySet(any());
        verify(grading, never()).gradeIfReady(any());
    }

    @Test
    @DisplayName("TOP·BOT 사진 중 하나라도 분석 목록에서 빠지면 400이라 일부만 분석한 결과로 등급이 나지 않는다")
    void partialRejected() {
        List<PhotoRef> analyzed = new ArrayList<>(all());
        analyzed.remove(0);
        assertThatThrownBy(() -> service.save(BOOTH, set.getPublicId(), "v", List.of(), analyzed))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(grading, never()).gradeIfReady(any());
    }

    @Test
    @DisplayName("TOP·BOT 사진 전부를 분석했으면 흠집이 없어도(빈 detections) 받아서 등급 판정으로 넘긴다")
    void fullAccepted() {
        assertThat(service.save(BOOTH, set.getPublicId(), "v", List.of(), all())).isZero();
        assertThat(set.getDetectionsReceivedAt()).isNotNull();
        verify(grading).gradeIfReady(set);
    }

    private static List<PhotoRef> all() {
        List<PhotoRef> refs = new ArrayList<>();
        for (PhotoSpec s : specs()) {
            refs.add(new PhotoRef(s.face(), s.light(), s.camera()));
        }
        return refs;
    }

    private static List<PhotoSpec> specs() {
        List<PhotoSpec> specs = new ArrayList<>();
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        return specs;
    }
}
