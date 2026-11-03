package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/** 빠른 검사 세트를 정합 없이 등급 매기기(contracts specs/005-booth-quick-set). 저장소는 가짜라 Docker 없이 돈다. */
class QuickGradingTest {

    private static final String SHA = "b".repeat(64);
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));

    private DefectRepository defects;
    private DefectMatchRepository matches;
    private GradeResultRepository grades;
    private GradeEventPublisher events;
    private GradingService grading;

    @BeforeEach
    void setUp() {
        defects = mock(DefectRepository.class);
        matches = mock(DefectMatchRepository.class);
        grades = mock(GradeResultRepository.class);
        events = mock(GradeEventPublisher.class);
        when(grades.findByCheckpointId(anyString())).thenReturn(Optional.empty());
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        grading = new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, events);
    }

    @Test
    @DisplayName("빠른 검사 세트는 완료와 부스 탐지가 있으면 정합 없이 등급이 나오고, 흠집 0개면 A(문제 없음)")
    void noDefectsIsGradeA() {
        CaptureSet set = completedQuickSet();
        set.markDetectionsReceived();
        when(defects.findBySet(set)).thenReturn(List.of());

        assertThat(grading.gradeIfReady(set)).isTrue();

        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        assertThat(sent.getValue().grade()).isEqualTo(Grade.A);
        assertThat(sent.getValue().pins()).isEmpty();
        assertThat(sent.getValue().photos()).hasSize(12);
    }

    @Test
    @DisplayName("빠른 검사 세트의 긁힘 2개는 B, 찍힘이 있으면 C(문제 있음)")
    void defectsGiveGrade() {
        CaptureSet set = completedQuickSet();
        set.markDetectionsReceived();
        Photo top = photo(set, Face.TOP, Camera.CAM1);
        Photo bot = photo(set, Face.BOT, Camera.CAM2);
        when(defects.findBySet(set)).thenReturn(List.of(
                Defect.fromBooth(top, DefectClass.SCRATCH, BOX, 0.9, "yolo11-seg-v001"),
                Defect.fromBooth(bot, DefectClass.SCRATCH, BOX, 0.8, "yolo11-seg-v001")));
        grading.gradeIfReady(set);
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(events).publishGraded(sent.capture());
        assertThat(sent.getValue().grade()).isEqualTo(Grade.B);

        CaptureSet dented = completedQuickSet();
        dented.markDetectionsReceived();
        when(defects.findBySet(dented)).thenReturn(List.of(
                Defect.fromBooth(photo(dented, Face.TOP, Camera.CAM1), DefectClass.DENT, BOX, 0.9, "v")));
        GradeEventPublisher second = mock(GradeEventPublisher.class);
        new GradingService(defects, matches, mock(FaceAlignmentRepository.class), grades, second).gradeIfReady(dented);
        ArgumentCaptor<CheckpointGradedEvent> again = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        verify(second).publishGraded(again.capture());
        assertThat(again.getValue().grade()).isEqualTo(Grade.C);
    }

    @Test
    @DisplayName("같은 카메라의 L1·L2·L3 사진에 함께 잡힌 같은 흠집은 한 번만 세고, 다른 카메라는 따로 센다")
    void sameSpotAcrossLightsCountsOnce() {
        CaptureSet set = completedQuickSet();
        Photo topL1 = photo(set, Face.TOP, Camera.CAM1, Light.L1);
        Photo topL2 = photo(set, Face.TOP, Camera.CAM1, Light.L2);
        Photo topL3 = photo(set, Face.TOP, Camera.CAM1, Light.L3);
        Photo topCam2 = photo(set, Face.TOP, Camera.CAM2, Light.L2);
        List<Defect> found = List.of(
                Defect.fromBooth(topL1, DefectClass.SCRATCH, BOX, 0.7, "v"),
                Defect.fromBooth(topL2, DefectClass.SCRATCH, BOX, 0.9, "v"),
                Defect.fromBooth(topL3, DefectClass.SCRATCH, BOX, 0.8, "v"),
                Defect.fromBooth(topCam2, DefectClass.SCRATCH, BOX, 0.9, "v"));
        assertThat(GradingService.quickGroups(found, Set.of(Face.TOP, Face.BOT))).hasSize(2);
    }

    @Test
    @DisplayName("빠른 검사 세트도 부스 탐지가 오기 전에는 등급을 매기지 않는다")
    void waitsForDetections() {
        CaptureSet set = completedQuickSet();
        assertThat(grading.gradeIfReady(set)).isFalse();
        assertThat(set.getStatus()).isEqualTo(SetStatus.UPLOADED);
        verify(events, never()).publishGraded(any());
    }

    @Test
    @DisplayName("완료 전(UPLOADING)인 빠른 검사 세트는 탐지가 와도 등급을 매기지 않는다")
    void waitsForCompletion() {
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        set.markDetectionsReceived();
        assertThat(grading.gradeIfReady(set)).isFalse();
        verify(events, never()).publishGraded(any());
    }

    private static CaptureSet completedQuickSet() {
        CaptureSet set = CaptureSet.open("rental-000000000000001", "check-00000000000001", CaptureSource.BOOTH,
                "booth-1", null, CaptureProfile.QUICK, specs());
        Set<String> present = new HashSet<>();
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        return set;
    }

    private static Photo photo(CaptureSet set, Face face, Camera camera) {
        return photo(set, face, camera, Light.L1);
    }

    private static Photo photo(CaptureSet set, Face face, Camera camera, Light light) {
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == camera && p.getLight() == light).findFirst()
                .orElseThrow();
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
