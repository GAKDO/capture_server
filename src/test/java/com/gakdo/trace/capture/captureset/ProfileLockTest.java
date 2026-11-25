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
    @Test
    @DisplayName("기준은 완료된 세트가 가장 먼저 생긴 시점의 마지막 완료 세트 방식이다(다시 찍은 출고는 마지막 것)")
    void firstCheckpointLastCompletedSet() {
        List<SetInfo> sets = List.of(
                new SetInfo("out", CaptureProfile.QUICK, false),
                new SetInfo("out", CaptureProfile.QUICK, true),
                new SetInfo("out", CaptureProfile.FULL, true),
                new SetInfo("rcv", CaptureProfile.QUICK, true));
        ProfileLock.Reference ref = ProfileLock.reference(sets, "ret").orElseThrow();
        assertThat(ref.checkpointId()).isEqualTo("out");
        assertThat(ref.profile()).isEqualTo(CaptureProfile.FULL);
    }

    // [상황] 지금 찍는 시점의 세트만 있거나, 완료 안 된 세트만 있거나, 세트가 하나도 없다. 또 방식이 비어 있는 옛 세트가 있다.
    // [기대] 지금 찍는 시점·미완료 세트는 기준에서 빠지고, 남는 게 없으면 기준 없음(자유롭게 고름)이다.
    //        방식이 비어 있는 세트는 전체 검사로 본다(방식을 안 정하고 만든 세트의 기본값이 전체 검사).
    @Test
    @DisplayName("지금 찍는 시점과 완료 전 세트는 기준이 되지 않고, 남는 게 없으면 기준이 없다")
    void excludedAndIncomplete() {
        List<SetInfo> sets = List.of(new SetInfo("out", CaptureProfile.QUICK, true),
                new SetInfo("rcv", null, false));
        assertThat(ProfileLock.reference(sets, "out")).isEmpty();
        assertThat(ProfileLock.reference(List.of(), null)).isEmpty();
        assertThat(ProfileLock.reference(List.of(new SetInfo("out", null, true)), "rcv").orElseThrow().profile())
                .isEqualTo(CaptureProfile.FULL);
    }

    // [상황] 다른 방식으로 찍으려다 거부될 때의 안내 문장을 만든다.
    // [기대] 출고 때 방식(전체 검사(FULL) 또는 빠른 검사(QUICK))을 말하고, 빠른 검사면 부스에서만 찍을 수 있다고 알린다.
    @Test
    @DisplayName("거부 문장은 출고 방식을 말하고, 빠른 검사면 부스에서만 찍는다고 알린다")
    void message() {
        assertThat(ProfileLock.mismatchMessage(CaptureProfile.FULL)).contains("전체 검사(FULL)");
        assertThat(ProfileLock.mismatchMessage(CaptureProfile.QUICK)).contains("빠른 검사(QUICK)").contains("부스");
    }
}
