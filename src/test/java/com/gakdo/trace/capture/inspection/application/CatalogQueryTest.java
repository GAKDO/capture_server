// 이 파일이 확인하는 것: 공개 보유 기기 목록의 흠집 요약과 상세(11/8). 로그인 없이 누구나 보는 화면이므로
// 관리자가 확정한 흠집만 세고, 같은 흠집이 조명만 달리 여러 번 잡힌 것은 하나로 센다. 폰으로 찍은 세트는 공개하지 않는다.

package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
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

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSet.PhotoSpec;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.domain.Light;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService.Detail;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService.Summary;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DismissReason;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;
import com.gakdo.trace.capture.support.FakePhotoStorage;

/** 공개 보유 기기 목록의 흠집(11/8). 저장소는 가짜라 Docker 없이 돈다. */
class CatalogQueryTest {

    // 가짜 사진 해시값(64자리 "c")
    private static final String SHA = "c".repeat(64);
    // 사진 위 흠집 모양: 왼쪽 위 (10,10)~(50,30) 네모
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    // BOX와 멀리 떨어진 자리 (900,600)~(940,620) 네모. 다른 흠집으로 세게 하려고
    private static final List<List<Double>> FAR = List.of(List.of(900.0, 600.0), List.of(940.0, 600.0),
            List.of(940.0, 620.0), List.of(900.0, 620.0));

    // 가짜 흠집 저장소
    private DefectRepository defects;
    // 가짜 등급 결과 저장소
    private GradeResultRepository grades;
    // 시험할 대상: 공개 보유 기기 목록 서비스
    private CatalogQueryService service;

    // 테스트 도구: 가짜 흠집·등급 저장소와 가짜 사진 저장소로 공개 목록 서비스를 만든다.
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 흠집 저장소를 가짜로 만든다
        defects = mock(DefectRepository.class);
        // 준비: 등급 결과 저장소를 가짜로 만든다
        grades = mock(GradeResultRepository.class);
        // 준비: 가짜 저장소들, 가짜 사진 저장소, 사진 보기 URL 유효 시간 10분으로 시험 대상을 만든다
        service = new CatalogQueryService(grades, defects, new FakePhotoStorage(), Duration.ofMinutes(10));
    }

    // [상황] 부스 빠른 검사 세트에서 윗면 같은 자리에 L1 긁힘·L2 찍힘(같은 흠집), 바닥 먼 자리에 긁힘이 확정됐다.
    //        그 밖에 관리자가 "먼지"로 기각한 오염 하나와 아직 검수 대기인 깨짐 하나가 있다. 등급은 C다.
    // [기대] 흠집 수는 2개(윗면 묶음 1 + 바닥 1)이고 기각·검수 대기는 빠진다.
    //        종류별로는 묶음의 가장 심한 종류로 세서 찍힘 1·긁힘 1·오염 0·깨짐 0이다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("흠집 수는 확정한 흠집만, 조명만 다른 같은 흠집은 하나로 세고 기각·검수 대기는 뺀다")
    void countsConfirmedGroupsOnly() {
        // 준비: 다 올린 부스 빠른 검사 세트를 만든다
        CaptureSet set = gradedSet("check-00000000000001", CaptureSource.BOOTH);
        // 준비: 윗면 L1 사진의 BOX 자리 긁힘을 확정 상태로 만든다
        Defect l1 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L1), DefectClass.SCRATCH, BOX, 0.9, "v"));
        // 준비: 윗면 L2 사진의 같은 자리 찍힘을 확정 상태로 만든다 (조명만 다른 같은 흠집)
        Defect l2 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L2), DefectClass.DENT, BOX, 0.8, "v"));
        // 준비: 바닥 L1 사진의 먼 자리 긁힘을 확정 상태로 만든다
        Defect bot = confirmed(Defect.fromBooth(photo(set, Face.BOT, Light.L1), DefectClass.SCRATCH, FAR, 0.7, "v"));
        // 준비: 바닥 L2 사진의 오염 하나를 만들고
        Defect dismissed = Defect.fromBooth(photo(set, Face.BOT, Light.L2), DefectClass.STAIN, BOX, 0.6, "v");
        // 관리자가 "먼지"라며 기각한 상태로 바꾼다
        dismissed.review(ReviewStatus.DISMISSED, DismissReason.DUST, "admin", OffsetDateTime.now());
        // 준비: 바닥 L3 사진의 깨짐 하나를 만든다 (검수 안 함 = 검수 대기)
        Defect pending = Defect.fromBooth(photo(set, Face.BOT, Light.L3), DefectClass.CRACK, BOX, 0.6, "v");
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 이 세트의 흠집을 물으면 위 5개를 돌려주게 한다
        when(defects.findBySet(set)).thenReturn(List.of(l1, l2, bot, dismissed, pending));
        // 준비: 이 세트에 등급 C를 기록한다
        GradeResult grade = grade(set, Grade.C);
        // 준비: 이 시점들의 등급을 물으면 위 등급 결과를 돌려주게 한다
        when(grades.findByCheckpointIdIn(Set.of(set.getCheckpointId()))).thenReturn(List.of(grade));

        // 실행: 이 시점 하나의 공개 목록 요약을 만든다
        List<Summary> list = service.summaries(List.of(set.getCheckpointId()));

        // (도구) assertThat(목록).hasSize(n) = 목록 개수가 n이어야 한다는 검사
        // 확인: 요약은 1건이어야 한다
        assertThat(list).hasSize(1);
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 등급은 C여야 한다
        assertThat(list.get(0).grade()).isEqualTo("C");
        // 확인: 흠집 수는 윗면 묶음 1 + 바닥 1 = 2개여야 한다 (기각·검수 대기는 빠짐)
        assertThat(list.get(0).defectCount()).isEqualTo(2);
        // 묶음의 가장 심한 종류로 센다: 긁힘+찍힘 묶음은 찍힘
        // (도구) containsEntry(키, 값) = 맵에 그 키가 그 값으로 들어 있어야 한다는 검사
        // 확인: 종류별 수는 찍힘 1·긁힘 1·오염 0·깨짐 0이어야 한다
        assertThat(list.get(0).defectsByClass()).containsEntry("dent", 1).containsEntry("scratch", 1)
                .containsEntry("stain", 0).containsEntry("crack", 0);
    }

    // [상황] 폰으로 찍어 등급 A가 난 세트가 있다.
    // [기대] 공개 목록에서 빠지고, 상세를 열면 404다.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("폰으로 찍은 세트는 공개 목록에서 빠지고 상세는 404")
    void phoneSetIsHidden() {
        // 준비: 폰으로 찍은 세트(가짜)를 만든다
        CaptureSet set = gradedSet("check-00000000000002", CaptureSource.PHONE);
        // 준비: 이 세트에 등급 A를 기록한다
        GradeResult grade = grade(set, Grade.A);
        // 준비: 목록용 등급 조회에 이 등급을 돌려주게 한다
        when(grades.findByCheckpointIdIn(Set.of(set.getCheckpointId()))).thenReturn(List.of(grade));
        // 준비: 상세용 등급 조회에도 이 등급을 돌려주게 한다
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(grade));

        // (도구) isEmpty() = 목록이 비어 있어야 한다는 검사
        // 실행+확인: 공개 목록 요약에 이 세트가 빠져 비어 있어야 한다
        assertThat(service.summaries(List.of(set.getCheckpointId()))).isEmpty();
        // 준비: 시점 ID를 변수에 담아 둔다
        String checkpointId = set.getCheckpointId();
        // (도구) assertThatThrownBy(() -> 코드).isInstanceOf(종류) = 그 코드를 실행하면 이 종류의 예외가 나야 한다는 검사
        // 실행+확인: 상세를 열면 도메인 예외(404 찾을 수 없음)가 나야 한다
        assertThatThrownBy(() -> service.detail(checkpointId)).isInstanceOf(DomainException.class);
    }

    // [상황] 윗면 같은 자리에 L1·L2로 잡힌 긁힘 두 개가 확정된 세트의 상세를 연다.
    // [기대] 사진 12장의 보기 URL(https)을 주고, 사진 위 흠집 2개는 같은 묶음 ID를 공유하며, 흠집 수는 1개다.
    @Test
    @DisplayName("상세는 사진 보기 URL과 확정 흠집(원본 px·묶음 ID)을 주고, 같은 묶음은 대표 ID를 공유한다")
    void detailHasPhotosAndGroupedDefects() {
        // 준비: 다 올린 부스 빠른 검사 세트를 만든다
        CaptureSet set = gradedSet("check-00000000000003", CaptureSource.BOOTH);
        // 준비: 윗면 L1 사진의 BOX 자리 긁힘을 확정 상태로 만든다
        Defect l1 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L1), DefectClass.SCRATCH, BOX, 0.9, "v"));
        // 준비: 윗면 L2 사진의 같은 자리 긁힘을 확정 상태로 만든다
        Defect l2 = confirmed(Defect.fromBooth(photo(set, Face.TOP, Light.L2), DefectClass.SCRATCH, BOX, 0.8, "v"));
        // 준비: 이 세트의 흠집을 물으면 위 둘을 돌려주게 한다
        when(defects.findBySet(set)).thenReturn(List.of(l1, l2));
        // 준비: 이 시점의 등급을 물으면 B를 돌려주게 한다
        when(grades.findByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(grade(set, Grade.B)));

        // 실행: 이 시점의 공개 상세를 연다
        Detail d = service.detail(set.getCheckpointId());

        // (도구) allSatisfy(p -> ...) = 목록의 모든 항목이 안의 검사를 통과해야 한다는 뜻. startsWith = 그 글자로 시작해야 함
        // 확인: 사진은 12장이고 모두 https로 시작하는 보기 URL이 있어야 한다
        assertThat(d.photos()).hasSize(12).allSatisfy(p -> assertThat(p.url()).startsWith("https://"));
        // 확인: 사진 위 흠집은 2개여야 한다 (원본 사진마다 따로 보임)
        assertThat(d.photoDefects()).hasSize(2);
        // 확인: 두 흠집은 같은 묶음 ID를 공유해야 한다
        assertThat(d.photoDefects().get(0).groupId()).isEqualTo(d.photoDefects().get(1).groupId());
        // 확인: 흠집 수는 묶음 기준 1개여야 한다
        assertThat(d.defectCount()).isEqualTo(1);
    }

    // 테스트 도구: 흠집을 관리자가 "흠집 맞음"으로 확정한 상태로 만든다.
    private static Defect confirmed(Defect d) {
        // 관리자(admin)가 지금 "흠집 맞음(CONFIRMED)"으로 검수한 상태로 바꾼다 (기각 사유는 없음)
        d.review(ReviewStatus.CONFIRMED, null, "admin", OffsetDateTime.now());
        // 확정된 흠집을 돌려준다
        return d;
    }

    // 테스트 도구: 그 세트에 등급 결과를 기록한다.
    private static GradeResult grade(CaptureSet set, Grade g) {
        // 이 시점의 빈 등급 결과를 만든다
        GradeResult r = new GradeResult(set.getCheckpointId());
        // 규칙 grade-rules-v1로 등급 g를 기록한다 (사유·판정 불가 면은 없음)
        r.recordGrade(set, g, "grade-rules-v1", List.of(), List.of());
        // 등급 결과를 돌려준다
        return r;
    }

    // 테스트 도구: 다 올린 부스 빠른 검사 세트를 만든다. 폰 세트는 출처·시점만 흉내 낸 가짜로 만든다.
    private static CaptureSet gradedSet(String checkpointId, CaptureSource source) {
        // 폰 세트를 달라고 하면
        if (source == CaptureSource.PHONE) {
            // 공개 여부는 출처만 보므로 폰 세트는 출처·시점만 흉내 낸다
            // 가짜 세트를 만들고
            CaptureSet phone = mock(CaptureSet.class);
            // 출처를 물으면 PHONE이라고 답하게 하고
            when(phone.getSource()).thenReturn(CaptureSource.PHONE);
            // 시점 ID를 물으면 받은 값을 답하게 한 뒤
            when(phone.getCheckpointId()).thenReturn(checkpointId);
            // 그 가짜를 돌려준다
            return phone;
        }
        // 부스 세트면 빠른 검사 12장 목록으로 진짜 세트를 연다
        CaptureSet set = CaptureSet.open("rental-000000000000001", checkpointId, source, "booth-1", null,
                CaptureProfile.QUICK, specs());
        // "올라온 사진" ID를 모을 빈 집합
        Set<String> present = new HashSet<>();
        // 모든 사진을 "올라옴"에 넣는다
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        // 세트를 완료한다 (요청 키는 세트마다 달라지게 집합의 해시값으로)
        set.complete(present, Map.of(), "key-" + present.hashCode(), SHA);
        // 완료된 세트를 돌려준다
        return set;
    }

    // 테스트 도구: 세트에서 그 면·조명의 CAM1 사진 하나를 찾는다.
    private static Photo photo(CaptureSet set, Face face, Light light) {
        // 세트 사진 중
        return set.getPhotos().stream()
                // 그 면·CAM1·그 조명인 것만 걸러
                .filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1 && p.getLight() == light)
                // 첫 번째를 돌려준다 (없으면 예외)
                .findFirst().orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 모든 사진에 같은 촬영 시각을 쓴다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-05T10:00:00+09:00");
        // 바닥·윗면마다
        for (Face face : List.of(Face.BOT, Face.TOP)) {
            // 조명 L1·L2·L3마다
            for (Light light : List.of(Light.L1, Light.L2, Light.L3)) {
                // CAM1 사진 한 장
                specs.add(new PhotoSpec(face, light, Camera.CAM1, "jpg", SHA, at));
                // CAM2 사진 한 장 → 2 × 3 × 2 = 12장
                specs.add(new PhotoSpec(face, light, Camera.CAM2, "jpg", SHA, at));
            }
        }
        // 완성된 12장 목록을 돌려준다
        return specs;
    }
}
