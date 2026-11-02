package com.gakdo.trace.capture.inspection.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.inspection.domain.DefectMatchRecord;
import com.gakdo.trace.capture.inspection.domain.GradeResult;
import com.gakdo.trace.capture.inspection.infra.DefectMatchRepository;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.GradeResultRepository;

/**
 * 시점의 흠집·매칭·등급(contracts getCheckpointInspection). 등급이 있으면 그 세트, 없으면 시점의 가장 최근 세트를 보여 준다.
 * 범위: ADMIN은 모두, 일반 고객은 rentals 클레임의 본인 렌탈만(gateway는 경로에 rentalId가 없어 볼 수 없다).
 */
@Service
public class InspectionQueryService {

    private final CaptureSetRepository sets;
    private final DefectRepository defects;
    private final DefectMatchRepository matches;
    private final GradeResultRepository grades;

    public InspectionQueryService(CaptureSetRepository sets, DefectRepository defects, DefectMatchRepository matches,
            GradeResultRepository grades) {
        this.sets = sets;
        this.defects = defects;
        this.matches = matches;
        this.grades = grades;
    }

    public record InspectionView(String checkpointId, String grade, String ruleVersion, List<String> reasons,
            List<DefectView> defects) {
    }

    public record DefectView(String defectId, String face, @JsonProperty("class") String defectClass, List<List<Double>> polygon,
            Double lengthMm, Double areaMm2, double confidence, String modelVersion, String source, MatchView match) {
    }

    public record MatchView(String status, String prevDefectId, Double distanceMm) {
    }

    /**
     * 시점에서 보여 줄 세트: 등급을 매긴 세트, 없으면 가장 최근 세트. 범위는 ADMIN 전부, 일반 고객은 본인 렌탈만, 촬영 JWT는 403.
     * 미디어 조회(MediaService)도 같은 규칙을 쓴다.
     */
    static CaptureSet visibleSet(Caller caller, String checkpointId, GradeResultRepository grades,
            CaptureSetRepository sets) {
        boolean customerLogin = "CUSTOMER".equals(caller.role()) && !caller.isCaptureToken();
        if (!caller.isAdmin() && !customerLogin) {
            throw new DomainException(ErrorCode.FORBIDDEN, "관리자나 고객 로그인 토큰만 볼 수 있습니다.");
        }
        CaptureSet set = grades.findByCheckpointId(checkpointId).map(GradeResult::getCaptureSet)
                .or(() -> sets.findFirstByCheckpointIdOrderByIdDesc(checkpointId))
                .orElseThrow(() -> DomainException.notFound("시점의 검사 결과"));
        if (!caller.isAdmin() && !caller.ownsRental(set.getRentalId())) {
            throw Caller.outOfScope();
        }
        return set;
    }

    @Transactional(readOnly = true)
    public InspectionView get(Caller caller, String checkpointId) {
        Optional<GradeResult> grade = grades.findByCheckpointId(checkpointId);
        CaptureSet set = visibleSet(caller, checkpointId, grades, sets);

        Map<Long, DefectMatchRecord> byDefect = new HashMap<>();
        matches.findBySet(set).forEach(m -> byDefect.put(m.getDefect().getId(), m));
        List<DefectView> list = defects.findBySet(set).stream()
                .filter(d -> d.getPolygon() != null)
                .map(d -> {
                    DefectMatchRecord m = byDefect.get(d.getId());
                    MatchView match = m == null ? null : new MatchView(m.getStatus().name(),
                            m.getPrevDefect() == null ? null : m.getPrevDefect().getPublicId(), m.getDistanceMm());
                    return new DefectView(d.getPublicId(), d.getPhoto().getFace().name(), d.getDefectClass().code(),
                            d.getPolygon(), d.getLengthMm(), d.getAreaMm2(), d.getConfidence(), d.getModelVersion(),
                            d.getSource().name(), match);
                })
                .toList();
        return grade.map(g -> new InspectionView(checkpointId, g.getGrade().name(), g.getRuleVersion(),
                g.getReasons(), list))
                .orElseGet(() -> new InspectionView(checkpointId, null, null, List.of(), list));
    }
}
