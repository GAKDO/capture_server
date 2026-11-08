package com.gakdo.trace.capture.inspection.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.CaptureSource;
import com.gakdo.trace.capture.captureset.domain.Quality;
import com.gakdo.trace.capture.captureset.domain.SetStatus;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.DomainException.FieldError;
import com.gakdo.trace.capture.common.domain.ErrorCode;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.Times;
import com.gakdo.trace.capture.inspection.application.InspectionQueryService.InspectionView;
import com.gakdo.trace.capture.inspection.domain.Defect;
import com.gakdo.trace.capture.inspection.domain.DismissReason;
import com.gakdo.trace.capture.inspection.domain.ReviewStatus;
import com.gakdo.trace.capture.inspection.infra.DefectRepository;
import com.gakdo.trace.capture.inspection.infra.ReviewQueueRepository;
import com.gakdo.trace.capture.inspection.infra.ReviewQueueRepository.QueueRow;

/**
 * 사람 검수(contracts listDefectReviews·saveDefectReviews·completeDefectReview, 11/8 민서 결정).
 * AI가 넓게 잡은 후보를 관리자가 흠집 맞음·아님으로 정하고, 검수를 완료하면 기각을 뺀 흠집으로 등급을 매긴다(GradingService).
 * 시점의 가장 최근 세트를 행 잠금으로 읽어, 부스 탐지 저장·정합 이벤트와 동시에 와도 한 번만 등급을 매긴다.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final CaptureSetRepository sets;
    private final DefectRepository defects;
    private final ReviewQueueRepository queue;
    private final GradingService grading;
    private final InspectionQueryService inspections;

    public ReviewService(CaptureSetRepository sets, DefectRepository defects, ReviewQueueRepository queue,
            GradingService grading, InspectionQueryService inspections) {
        this.sets = sets;
        this.defects = defects;
        this.queue = queue;
        this.grading = grading;
        this.inspections = inspections;
    }

    /** 결정 하나. reason은 DISMISSED에만. */
    public record Decision(String defectId, ReviewStatus decision, DismissReason reason) {
    }

    @Transactional(readOnly = true)
    public List<QueueRow> list(Caller caller, ReviewStatus status, int limit) {
        requireAdmin(caller);
        if (status == ReviewStatus.DISMISSED) {
            throw DomainException.invalid("status", "status는 PENDING이나 CONFIRMED만 됩니다.");
        }
        return status == ReviewStatus.CONFIRMED ? queue.done(limit) : queue.pending(limit);
    }

    /** 결정 저장. 모르는 defectId가 하나라도 있으면 아무것도 바꾸지 않고 404. 등급은 매기지 않는다. */
    @Transactional
    public InspectionView save(Caller caller, String adminId, String checkpointId, List<Decision> items) {
        requireAdmin(caller);
        validate(items);
        CaptureSet set = lockLatest(checkpointId);
        if (set.getReviewCompletedAt() != null || set.getStatus() == SetStatus.GRADED) {
            throw new DomainException(ErrorCode.REVIEW_CLOSED, "검수를 완료했거나 이미 등급이 난 시점입니다.");
        }
        Map<String, Defect> byId = new HashMap<>();
        GradingService.reviewCandidates(set, defects.findBySet(set)).forEach(d -> byId.put(d.getPublicId(), d));
        List<String> unknown = items.stream().map(Decision::defectId).filter(id -> !byId.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND, "이 시점의 검수 후보가 아닌 흠집입니다: " + String.join(", ", unknown));
        }
        var now = Times.now();
        for (Decision item : items) {
            byId.get(item.defectId()).review(item.decision(), item.reason(), adminId, now);
        }
        defects.flush();
        log.info("검수 저장 checkpoint={} set={} 결정={} by={}", checkpointId, set.getPublicId(), items.size(), adminId);
        return inspections.get(caller, checkpointId);
    }

    /**
     * 검수 완료 → 등급. 같은 Idempotency-Key로 다시 오면 다시 매기지 않고 지금 결과를 돌려준다.
     * 준비 전(탐지·정합 대기)이면 409 REVIEW_NOT_READY, PENDING이 남았으면 409 REVIEW_PENDING_REMAINS, 이미 완료했거나 후보가 없던 세트면 409 REVIEW_CLOSED.
     */
    @Transactional
    public InspectionView complete(Caller caller, String adminId, String checkpointId, String idempotencyKey) {
        requireAdmin(caller);
        CaptureSet set = lockLatest(checkpointId);
        if (set.getReviewCompletedAt() != null) {
            if (idempotencyKey.equals(set.getReviewCompletionKey())) {
                return inspections.get(caller, checkpointId);
            }
            throw new DomainException(ErrorCode.REVIEW_CLOSED, "이미 검수를 완료한 시점입니다.");
        }
        if (set.getStatus() == SetStatus.GRADED || set.getQuality() == Quality.UNJUDGEABLE) {
            throw new DomainException(ErrorCode.REVIEW_CLOSED, "이미 등급이 났거나 판정할 면이 없어 다시 찍어야 하는 시점입니다.");
        }
        if (!ready(set)) {
            throw new DomainException(ErrorCode.REVIEW_NOT_READY, "아직 촬영 완료·탐지·정합 결과가 다 오지 않았습니다.");
        }
        List<Defect> candidates = GradingService.reviewCandidates(set, defects.findBySet(set));
        if (candidates.isEmpty()) {
            throw new DomainException(ErrorCode.REVIEW_CLOSED, "검수할 후보가 없는 시점입니다(탐지 직후 등급을 매김).");
        }
        long pending = candidates.stream().filter(d -> d.getReviewStatus() == ReviewStatus.PENDING).count();
        if (pending > 0) {
            throw new DomainException(ErrorCode.REVIEW_PENDING_REMAINS, "검수하지 않은 후보가 " + pending + "개 남았습니다.",
                    List.of(), Map.of("pending", pending));
        }
        set.completeReview(adminId, idempotencyKey);
        boolean graded = grading.gradeIfReady(set);
        sets.flush();
        log.info("검수 완료 checkpoint={} set={} 후보={} 등급={} by={}", checkpointId, set.getPublicId(), candidates.size(),
                graded, adminId);
        return inspections.get(caller, checkpointId);
    }

    /** 등급을 매길 재료가 다 왔나(GradingService의 준비 조건과 같다). */
    static boolean ready(CaptureSet set) {
        if (set.isQuick()) {
            return set.getStatus() == SetStatus.UPLOADED && set.getDetectionsReceivedAt() != null;
        }
        return set.getStatus() == SetStatus.INSPECTED
                && (set.getSource() != CaptureSource.BOOTH || set.getDetectionsReceivedAt() != null);
    }

    /** 요청 안 규칙: 같은 defectId 두 번 금지, DISMISSED에는 reason 필수, 다른 결정에는 reason 금지. 어기면 400(어느 항목인지). */
    static void validate(List<Decision> items) {
        List<FieldError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Decision d = items.get(i);
            if (!seen.add(d.defectId())) {
                errors.add(new FieldError("items[" + i + "].defectId", "같은 흠집을 두 번 보냈습니다."));
            }
            if (d.decision() == ReviewStatus.DISMISSED && d.reason() == null) {
                errors.add(new FieldError("items[" + i + "].reason", "흠집 아님(DISMISSED)에는 사유가 필요합니다."));
            } else if (d.decision() != ReviewStatus.DISMISSED && d.reason() != null) {
                errors.add(new FieldError("items[" + i + "].reason", "사유는 흠집 아님(DISMISSED)에만 줍니다."));
            }
        }
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "검수 결정이 올바르지 않습니다.", errors);
        }
    }

    private CaptureSet lockLatest(String checkpointId) {
        // 엔티티를 먼저 읽으면 잠금 뒤에도 영속성 컨텍스트의 옛 상태를 보므로 ID만 찾고 잠가서 읽는다
        String setId = sets.findLatestPublicIdByCheckpointId(checkpointId)
                .orElseThrow(() -> DomainException.notFound("시점의 촬영 세트"));
        return sets.findForUpdate(setId).orElseThrow(() -> DomainException.notFound("시점의 촬영 세트"));
    }

    private static void requireAdmin(Caller caller) {
        if (!caller.isAdmin()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "관리자만 검수할 수 있습니다.");
        }
    }
}
