// 이 파일이 확인하는 것: 검사 방식 고정(11/13). 수령·반납 때는 출고 때와 같은 검사 방식(빠른 QUICK/전체 FULL)으로만 찍게 한다.
// 같은 방식으로 찍어야 출고 사진과 반납 사진을 같은 조건으로 비교할 수 있기 때문이다.
// 여기서는 "기준 방식"을 어느 촬영 세트에서 가져오는지와 거부 안내 문장을 본다.

package com.gakdo.trace.capture.captureset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.ProfileLock;
import com.gakdo.trace.capture.captureset.domain.ProfileLock.SetInfo;

class ProfileLockTest {

    // [상황] 출고(out)에서 빠른 검사로 찍었다가 전체 검사로 다시 찍었고, 수령(rcv)은 빠른 검사로 찍었다. 지금은 반납(ret)이다.
    // [기대] 기준은 가장 먼저 완료된 시점(출고)이고, 그 안에서 마지막으로 완료된 세트의 방식(전체 검사)이다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("기준은 완료된 세트가 가장 먼저 생긴 시점의 마지막 완료 세트 방식이다(다시 찍은 출고는 마지막 것)")
    void firstCheckpointLastCompletedSet() {
        // 준비: 촬영 세트 정보 4개를 만든다 (시점 ID, 검사 방식, 완료 여부)
        List<SetInfo> sets = List.of(
                // 출고: 빠른 검사, 완료 안 됨
                new SetInfo("out", CaptureProfile.QUICK, false),
                // 출고: 빠른 검사, 완료
                new SetInfo("out", CaptureProfile.QUICK, true),
                // 출고: 전체 검사로 다시 찍어 완료 (출고의 마지막 완료 세트)
                new SetInfo("out", CaptureProfile.FULL, true),
                // 수령: 빠른 검사, 완료
                new SetInfo("rcv", CaptureProfile.QUICK, true));
        // 실행: 지금 반납(ret)을 찍는다고 보고 기준 방식을 구한다 (orElseThrow = 값이 없으면 실패)
        ProfileLock.Reference ref = ProfileLock.reference(sets, "ret").orElseThrow();
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 기준 시점은 가장 먼저 완료된 출고여야 한다
        assertThat(ref.checkpointId()).isEqualTo("out");
        // 확인: 기준 방식은 출고의 마지막 완료 세트인 전체 검사(FULL)여야 한다
        assertThat(ref.profile()).isEqualTo(CaptureProfile.FULL);
    }

    // [상황] 지금 찍는 시점의 세트만 있거나, 완료 안 된 세트만 있거나, 세트가 하나도 없다. 또 방식이 비어 있는 옛 세트가 있다.
    // [기대] 지금 찍는 시점·미완료 세트는 기준에서 빠지고, 남는 게 없으면 기준 없음(자유롭게 고름)이다.
    //        방식이 비어 있는 세트는 전체 검사로 본다(방식을 안 정하고 만든 세트의 기본값이 전체 검사).
    @Test
    @DisplayName("지금 찍는 시점과 완료 전 세트는 기준이 되지 않고, 남는 게 없으면 기준이 없다")
    void excludedAndIncomplete() {
        // 준비: 출고 완료 세트(빠른 검사)와 수령 미완료 세트(방식 비어 있음)를 만든다
        List<SetInfo> sets = List.of(new SetInfo("out", CaptureProfile.QUICK, true),
                // 수령은 완료 전이라 기준이 될 수 없다
                new SetInfo("rcv", null, false));
        // 실행+확인: 지금 출고를 찍는다면 출고 세트는 빼고 수령은 미완료라 기준이 없어야(빈 값) 한다
        assertThat(ProfileLock.reference(sets, "out")).isEmpty();
        // 실행+확인: 세트가 하나도 없으면 기준이 없어야 한다
        assertThat(ProfileLock.reference(List.of(), null)).isEmpty();
        // 실행: 방식이 비어 있는 완료된 옛 출고 세트만 있을 때 수령의 기준을 구한다
        assertThat(ProfileLock.reference(List.of(new SetInfo("out", null, true)), "rcv").orElseThrow().profile())
                // 확인: 비어 있는 방식은 전체 검사(FULL)로 봐야 한다
                .isEqualTo(CaptureProfile.FULL);
    }

    // [상황] 다른 방식으로 찍으려다 거부될 때의 안내 문장을 만든다.
    // [기대] 출고 때 방식(전체 검사(FULL) 또는 빠른 검사(QUICK))을 말하고, 빠른 검사면 부스에서만 찍을 수 있다고 알린다.
    @Test
    @DisplayName("거부 문장은 출고 방식을 말하고, 빠른 검사면 부스에서만 찍는다고 알린다")
    void message() {
        // (도구) contains(글자) = 결과 글자에 그 부분이 들어 있어야 한다는 검사
        // 실행+확인: 출고가 전체 검사일 때의 거부 문장에 "전체 검사(FULL)"가 들어 있어야 한다
        assertThat(ProfileLock.mismatchMessage(CaptureProfile.FULL)).contains("전체 검사(FULL)");
        // 실행+확인: 출고가 빠른 검사일 때의 거부 문장에 "빠른 검사(QUICK)"와 "부스"가 들어 있어야 한다
        assertThat(ProfileLock.mismatchMessage(CaptureProfile.QUICK)).contains("빠른 검사(QUICK)").contains("부스");
    }
}
