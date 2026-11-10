package com.gakdo.trace.capture.inspection.application;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.inspection.domain.CheckpointKind;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DefectGroups;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.domain.SettlementRules;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;

/**
 * 반납 정산서(contracts getCheckpointSettlement, 11/10). 반납(RET) 시점의 신규 흠집 묶음을 규칙표에 넣어 차감액을 계산한다.
 * 직원이 흠집을 보고 견적을 내던 일을 등급과 같은 근거(검수로 확정된 흠집·비교 결과)로 바로 내므로, 검수가 끝나면 정산도 끝난다.
 * 범위는 검사 결과 조회와 같다(ADMIN 전부, 고객은 본인 렌탈).
 */
@Service
public class SettlementService {

    private final CaptureSetRepository sets;
    private final GradeResultRepository grades;
    private final DefectRepository defects;
    private final GradingService grading;
    private final int cap;

    public SettlementService(CaptureSetRepository sets, GradeResultRepository grades, DefectRepository defects,
            GradingService grading, @Value("${app.settlement.cap:300000}") int cap) {
        this.sets = sets;
        this.grades = grades;
        this.defects = defects;
        this.grading = grading;
        this.cap = cap;
    }

    public record SettlementView(String checkpointId, String checkpointType, String status, String ruleVersion,
            String currency, String grade, List<LineView> lines, int subtotal, int cap, int total,
            List<String> notes) {
    }

    public record LineView(String defectId, String face, @JsonProperty("class") String defectClass, Double lengthMm,
            Double areaMm2, int amount, String rule) {
    }

    @Transactional(readOnly = true)
    public SettlementView get(Caller caller, String checkpointId) {
        CaptureSet set = InspectionQueryService.visibleSet(caller, checkpointId, grades, sets);
        Optional<GradeResult> graded = grades.findByCheckpointId(checkpointId);
        if (graded.isEmpty() || graded.get().getGrade() == null) {
            return empty(checkpointId, "RET", "PENDING", null, List.of("검수·등급이 끝나면 계산됩니다"));
        }
        GradeResult result = graded.get();
        CheckpointKind kind = CheckpointKind.ofPosition(grades.countByCaptureSetRentalIdAndCheckpointIdNotAndCreatedAtBefore(
                set.getRentalId(), checkpointId, result.getCreatedAt()));
        String grade = result.getGrade().name();
        if (kind != CheckpointKind.RET) {
            return empty(checkpointId, kind.name(), "NOT_APPLICABLE", grade,
                    List.of(kind == CheckpointKind.RCV ? "수령 때 새로 생긴 흠집은 배송 중 손상이라 고객에게 차감하지 않습니다"
                            : "출고는 기준선이라 정산이 없습니다"));
        }
        CaptureSet gradedSet = result.getCaptureSet() == null ? set : result.getCaptureSet();
        Map<String, Defect> byId = new HashMap<>();
        defects.findBySet(gradedSet).forEach(d -> byId.put(d.getPublicId(), d));
        List<DefectGroups.Group> groups = grading.gradedGroups(gradedSet);

        // 기준은 바로 앞 시점(보통 수령). 앞 시점도 전체 검사일 때만 위치 비교(NEW)를 쓴다. 출고가 전체·수령이 빠른 검사면
        // 위치 비교는 출고와 견주게 되어 배송 중 손상까지 NEW가 되므로, 그때는 수령과 면·종류별 수로 견준다.
        Optional<CaptureSet> previousSet = grades
                .findFirstByCaptureSetRentalIdAndCheckpointIdNotOrderByUpdatedAtDesc(set.getRentalId(), checkpointId)
                .map(GradeResult::getCaptureSet);
        boolean compared = !gradedSet.isQuick() && previousSet.map(p -> !p.isQuick()).orElse(false);
        SettlementRules.Outcome outcome;
        if (compared) {
            List<SettlementRules.Item> fresh = groups.stream().filter(g -> Boolean.TRUE.equals(g.isNew()))
                    .map(g -> item(g, byId)).toList();
            outcome = SettlementRules.compared(fresh, groups.size() - fresh.size(), cap);
        } else {
            List<SettlementRules.Item> previous = previousSet.map(prev -> {
                Map<String, Defect> prevById = new HashMap<>();
                defects.findBySet(prev).forEach(d -> prevById.put(d.getPublicId(), d));
                return grading.gradedGroups(prev).stream().map(g -> item(g, prevById)).toList();
            }).orElse(List.of());
            outcome = SettlementRules.countDiff(groups.stream().map(g -> item(g, byId)).toList(), previous, cap);
        }
        List<LineView> lines = outcome.lines().stream()
                .map(l -> new LineView(l.defectId(), l.face().name(), l.defectClass().code(), l.lengthMm(),
                        l.areaMm2(), l.amount(), l.rule()))
                .toList();
        return new SettlementView(checkpointId, "RET", "READY", SettlementRules.VERSION, "KRW", grade, lines,
                outcome.subtotal(), outcome.cap(), outcome.total(), outcome.notes());
    }

    /** 묶음의 대표: 넓이가 가장 큰 흠집(모두 모르면 첫 흠집). 길이는 묶음 안 가장 긴 값. */
    private static SettlementRules.Item item(DefectGroups.Group g, Map<String, Defect> byId) {
        Defect rep = g.defectIds().stream().map(byId::get).filter(java.util.Objects::nonNull)
                .max(Comparator.comparing((Defect d) -> d.getAreaMm2() == null ? -1.0 : d.getAreaMm2()))
                .orElse(null);
        String id = rep == null ? g.defectIds().get(0) : rep.getPublicId();
        return new SettlementRules.Item(id, g.face(), g.defectClass(), g.lengthMm(),
                rep == null ? null : rep.getAreaMm2());
    }

    private SettlementView empty(String checkpointId, String type, String status, String grade, List<String> notes) {
        return new SettlementView(checkpointId, type, status, SettlementRules.VERSION, "KRW", grade, List.of(), 0, cap,
                0, notes);
    }
}
