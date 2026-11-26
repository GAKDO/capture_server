// 이 파일이 확인하는 것: 놓친 흠집 직접 추가(11/9). AI가 놓친 흠집을 관리자가 사진 위에 그려 더하면 등급에 들어간다.
// 또 AI 후보가 하나도 없는 세트도 바로 등급을 내지 않고 관리자 검수를 기다린다(사람이 한 번은 보게).

package com.gakdo.trace.capture.inspection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.comparison.domain.DefectClass;
import com.gakdo.trace.capture.inspection.application.ReviewService.HumanDefect;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DefectSource;
import com.gakdo.trace.capture.inspection.domain.Grade;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.FaceAlignmentRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;
import com.gakdo.trace.capture.inspection.infra.ReviewQueueRepository;

/** 놓친 흠집 직접 추가(11/9): 관리자가 그린 흠집이 등급에 들어가고, 후보 없는 세트도 검수를 기다린다. Docker 없이 돈다. */
class HumanDefectTest {

    // 가짜 사진 해시값(64자리 "c")
    private static final String SHA = "c".repeat(64);
    // 검수하는 관리자 ID
    private static final String ADMIN_ID = "admin00000000000000001";
    // 관리자가 그린 흠집 모양: (10,10)~(50,30) 네모
    private static final List<List<Double>> BOX = List.of(List.of(10.0, 10.0), List.of(50.0, 10.0),
            List.of(50.0, 30.0), List.of(10.0, 30.0));
    // 요청한 사람: 관리자
    private static final Caller ADMIN = new Caller("ADMIN", null, null, List.of());

    // 가짜 흠집 저장소
    private DefectRepository defects;
    // 가짜 등급 이벤트 발행기 (이벤트가 나갔는지 확인하려고)
    private GradeEventPublisher events;
    // 가짜 세트 저장소
    private CaptureSetRepository sets;
    // 등급 서비스 (진짜)
    private GradingService grading;
    // 시험할 대상: 검수 서비스
    private ReviewService reviews;
    // 흠집 저장소 대신 쓰는 메모리 목록
    private final List<Defect> stored = new ArrayList<>();

    // 테스트 도구: 메모리 목록(stored)을 흠집 저장소처럼 쓰고, 검수 켬·후보 없는 세트도 기다림 설정으로 등급·검수 서비스를 만든다.
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전마다 실행한다 (테스트끼리 영향을 주지 않게)
    @BeforeEach
    void setUp() {
        // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값(null·false·0·빈 목록)을 돌려주고, 불린 기록을 남긴다
        // 준비: 흠집 저장소를 가짜로 만든다
        defects = mock(DefectRepository.class);
        // 준비: 등급 결과 저장소를 가짜로 만든다
        GradeResultRepository grades = mock(GradeResultRepository.class);
        // 준비: 이벤트 발행기를 가짜로 만든다
        events = mock(GradeEventPublisher.class);
        // 준비: 세트 저장소를 가짜로 만든다
        sets = mock(CaptureSetRepository.class);
        // (도구) when(가짜.메서드()).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. anyString() = 아무 글자
        // 준비: 어떤 시점의 등급을 물어도 "아직 없음"으로 답하게 한다
        when(grades.findByCheckpointId(anyString())).thenReturn(Optional.empty());
        // 준비: 같은 렌탈의 앞 시점 등급도 "없음"으로 답하게 한다 (출고처럼 비교 없이)
        when(grades.findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        // (도구) thenAnswer(inv -> ...) = 정해진 값 대신, 불릴 때마다 이 코드를 실행해 결과를 만든다
        // 준비: 세트의 흠집을 물으면 그때의 메모리 목록 사본을 돌려주게 한다
        when(defects.findBySet(any())).thenAnswer(inv -> List.copyOf(stored));
        // 준비: 흠집 저장을 부르면
        when(defects.save(any(Defect.class))).thenAnswer(inv -> {
            // 받은 흠집(첫 번째 인자)을 메모리 목록에 넣고
            stored.add(inv.getArgument(0));
            // 그 흠집을 그대로 돌려주게 한다
            return inv.getArgument(0);
        });
        // (도구) doAnswer(코드).when(가짜).메서드(...) = 돌려줄 값이 없는(void) 메서드가 불릴 때 이 코드를 실행하게 한다
        // 준비: 흠집 삭제를 부르면 메모리 목록에서 빼게 한다
        doAnswer(inv -> stored.remove(inv.<Defect>getArgument(0))).when(defects).delete(any(Defect.class));
        // 준비: 검수 켬(true)·후보 없는 세트도 기다림(true)으로 등급 서비스를 만든다
        grading = new GradingService(defects, mock(DefectMatchRepository.class), mock(FaceAlignmentRepository.class),
                grades, events, true, true);
        // 준비: 위 가짜들과 등급 서비스로 검수 서비스를 만든다
        reviews = new ReviewService(sets, defects, mock(ReviewQueueRepository.class), grading,
                mock(InspectionQueryService.class), mock(FaceAlignmentRepository.class));
    }

    // [상황] AI 후보가 없는 빠른 검사 세트에 탐지 결과가 왔다.
    // [기대] 바로 등급을 내지 않고(이벤트도 없음) 검수 칸이 열린다. 관리자가 검수를 완료하면 그때 등급 A가 난다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("AI 후보가 없는 세트도 바로 등급을 매기지 않고 검수를 기다리며, 검수 칸이 열린다")
    void emptySetWaitsForReview() {
        // 준비: 탐지 결과까지 받은 빠른 검사 세트(흠집 없음)를 만들고 저장소에서 찾을 수 있게 한다
        CaptureSet set = lockable(readySet(Map.of()));

        // (도구) assertThat(값).isFalse() / isTrue() = 값이 거짓 / 참이어야 한다는 검사
        // 실행+확인: 등급을 매겨 보면 "안 매김(false)"이어야 한다 (검수 기다림)
        assertThat(grading.gradeIfReady(set)).isFalse();
        // 실행+확인: 검수 켬·후보 없는 세트 기다림 설정에서 검수 칸이 열려 있어야 한다
        assertThat(ReviewService.open(set, stored, true, true)).isTrue();
        // (도구) verify(가짜, never()).메서드(...) = 그 메서드가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 등급 이벤트가 나가지 않았어야 한다
        verify(events, never()).publishGraded(any());

        // 실행: 관리자가 요청 키 review-key-0001로 검수를 완료한다
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 이제 나간 등급 이벤트의 등급이 A여야 한다
        assertThat(sent().grade()).isEqualTo(Grade.A);
    }

    // [상황] AI가 놓친 윗면 찍힘을 관리자가 그려 더하고 검수를 완료한다.
    // [기대] 사람이 그린 것(HUMAN)·확정·그린 관리자·확신도 1.0으로 저장되고, AI 후보 수에는 세지 않는다.
    //        검수 완료 때 이 찍힘이 등급에 들어가 C가 되고 세트는 등급 완료(GRADED)가 된다.
    @Test
    @DisplayName("AI가 놓친 찍힘을 관리자가 그려 더하면 HUMAN·확정으로 저장되고 검수 완료 때 등급(C)에 들어간다")
    void addedDefectCountsInGrade() {
        // 준비: 검수할 수 있는 빠른 검사 세트를 만든다
        CaptureSet set = lockable(readySet(Map.of()));

        // 실행: 관리자가 윗면 L1 CAM1 사진에 찍힘을 그려 더한다
        reviews.add(ADMIN, ADMIN_ID, set.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX));

        // (도구) singleElement().satisfies(d -> ...) = 목록에 딱 하나만 있어야 하고, 그 하나가 안의 검사를 통과해야 한다는 뜻
        // 확인: 저장된 흠집이 하나뿐이고 아래를 만족해야 한다
        assertThat(stored).singleElement().satisfies(d -> {
            // 확인: 출처는 사람이 그린 것(HUMAN)이어야 한다
            assertThat(d.getSource()).isEqualTo(DefectSource.HUMAN);
            // 확인: 이미 확정 상태여야 한다
            assertThat(d.getReviewStatus()).isEqualTo(ReviewStatus.CONFIRMED);
            // 확인: 그린 관리자 ID가 남아야 한다
            assertThat(d.getReviewedBy()).isEqualTo(ADMIN_ID);
            // 확인: 확신도는 1.0이어야 한다
            assertThat(d.getConfidence()).isEqualTo(1.0);
            // 확인: 모델 버전 칸에는 "사람" 표시가 들어가야 한다
            assertThat(d.getModelVersion()).isEqualTo(Defect.HUMAN_MODEL);
        });
        // (도구) isEmpty() = 목록이 비어 있어야 한다는 검사
        // 실행+확인: AI 후보 목록은 비어 있어야 한다
        assertThat(GradingService.reviewCandidates(set, stored)).isEmpty(); // AI 후보 수에는 세지 않는다
        // 실행: 검수를 완료한다
        reviews.complete(ADMIN, ADMIN_ID, set.getCheckpointId(), "review-key-0001");
        // 확인: 그린 찍힘이 들어가 등급 C여야 한다
        assertThat(sent().grade()).isEqualTo(Grade.C);
        // 확인: 세트는 등급 완료(GRADED) 상태여야 한다
        assertThat(set.getStatus()).isEqualTo(SetStatus.GRADED);
    }

    // [상황] AI 후보 하나와 관리자가 그린 흠집 하나가 있다. 둘 다 지우려 한다.
    // [기대] 관리자가 그린 것은 지워지고, AI 후보는 지울 수 없다(404, AI 후보는 기각으로만 처리).
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("직접 더한 흠집은 지울 수 있지만 AI 후보는 지울 수 없다(404)")
    void removeOnlyHuman() {
        // 준비: 검수할 수 있는 빠른 검사 세트를 만든다
        CaptureSet set = lockable(readySet(Map.of()));
        // 준비: 바닥 사진의 AI 긁힘 후보(확신도 0.3)를 만들고
        Defect ai = Defect.fromBooth(photo(set, Face.BOT), DefectClass.SCRATCH, BOX, 0.3, "aug-v004");
        // 메모리 목록에 넣는다
        stored.add(ai);
        // 준비: 관리자가 윗면에 긁힘을 그려 더한다
        reviews.add(ADMIN, ADMIN_ID, set.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.SCRATCH, BOX));
        // 준비: 두 번째로 저장된 것(관리자가 그린 것)을 꺼낸다
        Defect human = stored.get(1);

        // (도구) assertThatThrownBy(() -> 코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: AI 후보를 지우려 하면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.remove(ADMIN, ADMIN_ID, set.getCheckpointId(), ai.getPublicId()))
                // (도구) isInstanceOfSatisfying(종류, e -> ...) = 예외가 그 종류이고, 안의 검사도 통과해야 한다는 뜻
                // 확인: 오류 코드는 찾을 수 없음(404)이어야 한다
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        // 실행: 관리자가 그린 흠집을 지운다
        reviews.remove(ADMIN, ADMIN_ID, set.getCheckpointId(), human.getPublicId());
        // (도구) containsExactly(...) = 정확히 이 값들만 이 순서로 있어야 한다는 검사
        // 확인: AI 후보만 남아야 한다
        assertThat(stored).containsExactly(ai);
    }

    // [상황] ① 건너뛴 윗면에 흠집을 그림 ② 빠른 검사 세트에 없는 키보드 사진에 그림 ③ 좌표가 음수인 다각형
    //        ④ 아직 탐지 결과가 안 온 세트에 그림 ⑤ 이미 등급이 난 세트에 그림.
    // [기대] ①②③은 400, ④는 409 REVIEW_NOT_READY(검수 칸도 닫혀 있음), ⑤는 409 REVIEW_CLOSED(검수 칸 닫힘)다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("건너뛴 면·세트에 없는 사진·잘못된 다각형은 400, 탐지 전은 409 REVIEW_NOT_READY, 등급 뒤는 409 REVIEW_CLOSED")
    void rejectsBadAdditions() {
        // 준비: 윗면을 "덮개 고장"으로 건너뛴 세트를 만든다
        CaptureSet skipped = lockable(readySet(Map.of(Face.TOP, "덮개 고장")));
        // 실행+확인: ① 건너뛴 윗면에 흠집을 그리면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, skipped.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                // 확인: 입력값 검증 실패(400)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        // 실행+확인: ② 빠른 검사에 없는 키보드 사진에 그려도 예외가 나야 하고
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, skipped.getCheckpointId(),
                new HumanDefect(Face.KB, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                // 확인: 입력값 검증 실패(400)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        // 실행+확인: ③ 좌표에 음수(-2)가 있는 다각형은 예외가 나야 하고
        assertThatThrownBy(() -> ReviewService.validatePolygon(List.of(List.of(1.0, 1.0), List.of(-2.0, 3.0),
                List.of(4.0, 4.0))))
                // 확인: 입력값 검증 실패(400)여야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        // 준비: 다 올렸지만 탐지 결과가 아직 안 온 세트를 만든다
        CaptureSet waiting = lockable(completedSet(Map.of()));
        // 실행+확인: ④ 여기에 그리면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, waiting.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                // 확인: 오류 코드는 "아직 검수할 수 없음"(409)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_NOT_READY));
        // 실행+확인: 검수 칸도 닫혀 있어야 한다
        assertThat(ReviewService.open(waiting, stored, true, true)).isFalse();

        // 준비: 검수할 수 있는 세트를 만들고
        CaptureSet graded = lockable(readySet(Map.of()));
        // 검수를 완료해 등급을 낸다
        reviews.complete(ADMIN, ADMIN_ID, graded.getCheckpointId(), "review-key-0009");
        // 실행+확인: ⑤ 등급이 난 세트에 그리면 예외가 나야 하고
        assertThatThrownBy(() -> reviews.add(ADMIN, ADMIN_ID, graded.getCheckpointId(),
                new HumanDefect(Face.TOP, Light.L1, Camera.CAM1, DefectClass.DENT, BOX)))
                // 확인: 오류 코드는 "검수 이미 끝남"(409)이어야 한다
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_CLOSED));
        // 실행+확인: 검수 칸도 닫혀 있어야 한다
        assertThat(ReviewService.open(graded, stored, true, true)).isFalse();
    }

    // [상황] "후보 없는 세트는 기다리지 않기" 설정으로 서버를 띄운다.
    // [기대] 예전처럼 후보 없는 세트는 바로 등급이 나고 검수 칸은 닫힌다.
    @Test
    @DisplayName("후보 없는 세트를 기다리지 않는 설정이면 예전처럼 바로 등급이 나고 검수 칸이 닫힌다")
    void holdEmptyOff() {
        // 준비: 검수 켬(true)·후보 없는 세트 기다리지 않음(false)으로 등급 서비스를 만든다
        GradingService off = new GradingService(defects, mock(DefectMatchRepository.class),
                mock(FaceAlignmentRepository.class), mock(GradeResultRepository.class), events, true, false);
        // 준비: 흠집 없는 검수 가능 세트를 만든다
        CaptureSet set = readySet(Map.of());
        // 실행+확인: 이 설정에서는 검수 칸이 닫혀 있어야 한다 (바로 등급)
        assertThat(ReviewService.open(set, stored, true, false)).isFalse();
        // 실행+확인: 등급 서비스도 "기다리지 않음"으로 알려야 한다
        assertThat(off.isHoldEmpty()).isFalse();
    }

    // [상황] 검수 대기·완료 목록을 읽는 SQL을 확인한다.
    // [기대] 후보 없는 세트도 0건으로 목록에 싣고(LEFT JOIN, 없으면 0), 탐지 결과가 오기 전 세트는 뺀다. 개수 제한이 있고 완료 목록은 최근 완료 순이다.
    //        설정을 끄면 후보 없는 세트를 싣는 부분이 빠진다.
    @Test
    @DisplayName("대기 목록 SQL은 후보 없는 세트도 0건으로 싣되 탐지·정합 전 세트는 뺀다")
    void queueSqlIncludesEmptySets() {
        // 준비: "후보 없는 세트도 실음" 설정의 대기 목록 SQL을 만든다
        String sql = ReviewQueueRepository.pendingSql(true);
        // (도구) contains(글자) = 문장에 그 글자가 들어 있어야 한다는 검사
        // 확인: 후보 없는 세트도 붙이고(LEFT JOIN), 없으면 0으로 세며, 탐지 결과가 온 세트만, 개수 제한이 있어야 한다
        assertThat(sql).contains("LEFT JOIN per_set").contains("coalesce(ps.pending, 0)").contains("coalesce(ps.confirmed, 0)").contains("coalesce(ps.dismissed, 0)")
                .contains("detections_received_at IS NOT NULL").contains("LIMIT :limit");
        // (도구) doesNotContain(글자) = 그 글자가 없어야 한다는 검사
        // 실행+확인: 설정을 끄면 후보 없는 세트를 싣는 부분이 빠져야 한다
        assertThat(ReviewQueueRepository.pendingSql(false)).doesNotContain("LEFT JOIN per_set");
        // 실행+확인: 완료 목록 SQL도 후보 없는 세트를 싣고, 최근 완료 순으로 정렬해야 한다
        assertThat(ReviewQueueRepository.doneSql(true)).contains("LEFT JOIN per_set").contains("review_completed_at DESC");
    }

    // 테스트 도구: 그 시점의 최신 세트를 찾고 잠글 수 있게 가짜 세트 저장소에 넣어 둔다.
    private CaptureSet lockable(CaptureSet set) {
        // 이 시점의 최신 세트 ID를 물으면 이 세트 ID를 돌려주게 한다
        when(sets.findLatestPublicIdByCheckpointId(set.getCheckpointId())).thenReturn(Optional.of(set.getPublicId()));
        // 이 세트 ID로 잠가 읽으면 이 세트를 돌려주게 한다
        when(sets.findForUpdate(set.getPublicId())).thenReturn(Optional.of(set));
        // 세트를 그대로 돌려준다
        return set;
    }

    // 테스트 도구: 보낸 "시점 등급 완료" 이벤트를 꺼낸다.
    private CheckpointGradedEvent sent() {
        // (도구) ArgumentCaptor = 가짜에게 넘겨진 인자를 붙잡아 두는 도구 (무엇을 보냈는지 꺼내 보려고)
        // 등급 이벤트를 붙잡을 도구를 만든다
        ArgumentCaptor<CheckpointGradedEvent> sent = ArgumentCaptor.forClass(CheckpointGradedEvent.class);
        // (도구) verify(가짜).메서드(...) = 그 메서드가 불렸어야 한다는 검사
        // 이벤트 발행이 한 번 불렸는지 확인하면서 넘겨진 이벤트를 붙잡는다
        verify(events).publishGraded(sent.capture());
        // 붙잡은 이벤트를 돌려준다
        return sent.getValue();
    }

    // 세트마다 다른 시점 ID를 만들려고 세는 번호
    private static int seq;

    // 테스트 도구: 다 올리고 탐지 결과까지 받은 빠른 검사 세트를 만든다(검수할 수 있는 상태).
    private static CaptureSet readySet(Map<Face, String> skipped) {
        // 다 올린 세트를 만들고
        CaptureSet set = completedSet(skipped);
        // 탐지 결과를 받았다고 표시한다
        set.markDetectionsReceived();
        // 검수할 수 있는 세트를 돌려준다
        return set;
    }

    // 테스트 도구: 다 올렸지만 탐지 결과는 아직 없는 빠른 검사 세트를 만든다(시점 ID는 매번 새로).
    private static CaptureSet completedSet(Map<Face, String> skipped) {
        // 번호를 하나 올린다
        seq++;
        // "check-" + 15자리 번호를 시점 ID로 해 부스 빠른 검사 세트를 연다
        CaptureSet set = CaptureSet.open("rental-000000000000001", String.format("check-%015d", seq),
                CaptureSource.BOOTH, "booth-1", null, CaptureProfile.QUICK, specs());
        // "올라온 사진" ID를 모을 빈 집합
        Set<String> present = new HashSet<>();
        // 모든 사진을 "올라옴"에 넣는다
        set.getPhotos().forEach(p -> present.add(p.getPublicId()));
        // 받은 건너뛴 면 정보로 세트를 완료한다
        set.complete(present, skipped, "key-" + present.hashCode(), SHA);
        // 완료된 세트를 돌려준다
        return set;
    }

    // 테스트 도구: 세트에서 그 면의 CAM1·L1 사진을 찾는다.
    private static Photo photo(CaptureSet set, Face face) {
        // 세트 사진 중 그 면·CAM1·L1인 첫 사진을 돌려준다 (없으면 예외)
        return set.getPhotos().stream()
                .filter(p -> p.getFace() == face && p.getCamera() == Camera.CAM1 && p.getLight() == Light.L1)
                .findFirst().orElseThrow();
    }

    // 테스트 도구: 빠른 검사 12장 목록(BOT·TOP × L1~L3 × CAM1·CAM2)을 만든다.
    private static List<PhotoSpec> specs() {
        // 빈 목록을 만든다
        List<PhotoSpec> specs = new ArrayList<>();
        // 모든 사진에 같은 촬영 시각을 쓴다
        OffsetDateTime at = OffsetDateTime.parse("2026-11-09T10:00:00+09:00");
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
