package com.gakdo.trace.capture.inspection.domain;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.gakdo.trace.capture.captureset.domain.Camera;
import com.gakdo.trace.capture.captureset.domain.Face;

/**
 * 판정 불가 면(FR-CAP-04). 면마다, 그 면을 찍은 카메라별로 정합에 성공하고 품질이 통과한 사진이 한 장 이상 있어야 판정할 수 있다.
 * 부스 위 카메라 두 대(CAM1 앞 반·CAM2 뒤 반)가 면을 나눠 찍으므로 한 카메라가 모두 실패하면 면의 절반이 비어 판정 불가다.
 * 조명 하나가 실패한 것으로는 면을 버리지 않는다. 건너뛴 선택 면은 사유와 함께 판정 불가다.
 */
public final class Judgeability {

    private Judgeability() {
    }

    /**
     * @param uploaded 올라간 사진만(완료 때 저장소에서 확인된 것)
     * @param aligned  정합에 성공한 사진인가
     */
    public record PhotoState(Face face, Camera camera, boolean uploaded, boolean qualityOk, boolean aligned,
            String alignFailure) {

        /** 정합 실패 사유를 모를 때(테스트·예전 사진). */
        public PhotoState(Face face, Camera camera, boolean uploaded, boolean qualityOk, boolean aligned) {
            this(face, camera, uploaded, qualityOk, aligned, null);
        }
    }

    /** 판정 불가 면과 사람이 읽는 사유(면 순서). */
    public static Map<Face, String> unjudgeable(Collection<PhotoState> photos, Map<Face, String> skipped) {
        Map<Face, String> out = new EnumMap<>(Face.class);
        skipped.forEach((face, reason) -> out.put(face, "건너뜀(" + reason + ")"));

        Map<Face, Set<Camera>> shot = new EnumMap<>(Face.class);
        Map<Face, Set<Camera>> usable = new EnumMap<>(Face.class);
        Map<Face, Set<String>> failures = new EnumMap<>(Face.class);
        for (PhotoState p : photos) {
            if (!p.uploaded() || skipped.containsKey(p.face())) {
                continue;
            }
            shot.computeIfAbsent(p.face(), f -> EnumSet.noneOf(Camera.class)).add(p.camera());
            if (p.aligned() && p.qualityOk()) {
                usable.computeIfAbsent(p.face(), f -> EnumSet.noneOf(Camera.class)).add(p.camera());
            } else if (!p.aligned() && p.alignFailure() != null) {
                failures.computeIfAbsent(p.face(), f -> new TreeSet<>()).add(p.alignFailure());
            }
        }
        shot.forEach((face, cameras) -> {
            Set<Camera> missing = new TreeSet<>(cameras);
            missing.removeAll(usable.getOrDefault(face, Set.of()));
            if (!missing.isEmpty()) {
                String why = failures.containsKey(face) ? " - " + String.join(" / ", failures.get(face)) : "";
                out.put(face, "정합 실패 또는 품질 미달(" + String.join("·", missing.stream().map(Camera::name).toList())
                        + ")" + why);
            }
        });
        return out;
    }

    /** 판정할 수 있는 면 = 올라간 사진이 있는 면 - 판정 불가 면. */
    public static Set<Face> judgeable(Collection<PhotoState> photos, Map<Face, String> unjudgeable) {
        Set<Face> faces = EnumSet.noneOf(Face.class);
        photos.stream().filter(PhotoState::uploaded).forEach(p -> faces.add(p.face()));
        faces.removeAll(unjudgeable.keySet());
        return faces;
    }
}
