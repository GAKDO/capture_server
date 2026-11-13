package com.gakdo.trace.capture.captureset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.capture.captureset.domain.CaptureProfile;
import com.gakdo.trace.capture.captureset.domain.ProfileLock;
import com.gakdo.trace.capture.captureset.domain.ProfileLock.SetInfo;

class ProfileLockTest {

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

    @Test
    @DisplayName("거부 문장은 출고 방식을 말하고, 빠른 검사면 부스에서만 찍는다고 알린다")
    void message() {
        assertThat(ProfileLock.mismatchMessage(CaptureProfile.FULL)).contains("전체 검사(FULL)");
        assertThat(ProfileLock.mismatchMessage(CaptureProfile.QUICK)).contains("빠른 검사(QUICK)").contains("부스");
    }
}
