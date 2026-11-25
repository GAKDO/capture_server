package com.gakdo.trace.capture.captureset.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 검사 방식 고정(11/13 민서 요청): 한 렌탈의 수령·반납은 출고와 같은 profile(FULL·QUICK)로만 찍는다.
 * 반납은 앞 시점 기록과 면·사진을 맞춰 비교하므로 방식이 다르면 비교가 어긋난다.
 *
 * <p>기준은 이 렌탈의 다른 시점(지금 찍는 시점은 뺌) 중 완료된 세트가 가장 먼저 생긴 시점이고, 그 시점의 마지막 완료 세트 profile이다
 * (같은 시점을 다시 찍었으면 등급에 쓰인 마지막 세트를 따른다). 완료된 세트가 없으면 기준이 없어 어느 방식이든 된다.</p>
 */
public final class ProfileLock {

    /** 렌탈 세트 한 줄(만든 순서대로 넘긴다). */
    public record SetInfo(String checkpointId, CaptureProfile profile, boolean completed) {
    }

    /** 기준 시점과 그 방식. */
    public record Reference(String checkpointId, CaptureProfile profile) {
    }

    private ProfileLock() {
    }

    /** sets는 만든 순서(id 오름차순). exceptCheckpoint는 지금 찍으려는 시점(null이면 빼지 않음). */
    public static Optional<Reference> reference(List<SetInfo> sets, String exceptCheckpoint) {
        String first = null;
        CaptureProfile profile = null;
        for (SetInfo s : sets) {
            if (!s.completed() || Objects.equals(s.checkpointId(), exceptCheckpoint)) {
                continue;
            }
            if (first == null) {
                first = s.checkpointId();
            }
            if (first.equals(s.checkpointId())) {
                profile = s.profile() == null ? CaptureProfile.FULL : s.profile();
            }
        }
        return first == null ? Optional.empty() : Optional.of(new Reference(first, profile));
    }

    /** 고른 방식이 기준과 다를 때 보일 문장(부스·폰 화면에 그대로 나간다). */
    public static String mismatchMessage(CaptureProfile required) {
        return "이 렌탈은 출고를 " + label(required) + "(" + required.name() + ")로 했습니다. 수령·반납도 같은 방식으로만 찍을 수 있습니다"
                + (required == CaptureProfile.QUICK ? "(빠른 검사는 부스에서만 찍습니다)." : ".");
    }

    public static String label(CaptureProfile profile) {
        return profile == CaptureProfile.QUICK ? "빠른 검사" : "전체 검사";
    }
}
