package com.gakdo.trace.capture.inspection.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 사람 검수 대기·완료 시점 목록(contracts listDefectReviews, 11/8). 시점마다 가장 최근 세트 하나를 보고, 후보 수를 세트 단위로 센다.
 * 후보 조건은 GradingService.reviewCandidates와 같다: AUTO, 올라간 사진, 건너뛰지 않은 면, 전체 세트는 표준 좌표가 있는 흠집.
 * (jsonb ? 연산자는 NamedParameterJdbcTemplate이 자리표시자로 읽어 jsonb_exists 함수로 쓴다.)
 */
@Repository
public class ReviewQueueRepository {

    private static final String COUNTS = """
            WITH candidate AS (
              SELECT cs.id AS set_id, d.review_status, d.model_version, d.id AS defect_id
                FROM capture.capture_set cs
                JOIN capture.photo p ON p.capture_set_id = cs.id
                JOIN inspection.defect d ON d.photo_id = p.id
               WHERE d.source = 'AUTO' AND p.uploaded
                 AND NOT jsonb_exists(cs.skipped_faces, p.face)
                 AND (cs.profile = 'QUICK' OR d.polygon IS NOT NULL)
            ), per_set AS (
              SELECT set_id,
                     count(*) FILTER (WHERE review_status = 'PENDING') AS pending,
                     count(*) FILTER (WHERE review_status = 'CONFIRMED') AS confirmed,
                     count(*) FILTER (WHERE review_status = 'DISMISSED') AS dismissed,
                     (array_agg(model_version ORDER BY defect_id))[1] AS model_version
                FROM candidate GROUP BY set_id
            )
            SELECT cs.checkpoint_id, cs.rental_id, cs.public_id, cs.source, cs.profile, ps.model_version,
                   ps.pending, ps.confirmed, ps.dismissed, cs.completed_at, cs.review_completed_at
              FROM capture.capture_set cs
              JOIN per_set ps ON ps.set_id = cs.id
             WHERE cs.id = (SELECT max(o.id) FROM capture.capture_set o WHERE o.checkpoint_id = cs.checkpoint_id)
            """;

    private static final String PENDING = COUNTS + """
               AND cs.completed_at IS NOT NULL AND cs.review_completed_at IS NULL
               AND cs.status <> 'GRADED' AND cs.quality <> 'UNJUDGEABLE'
             ORDER BY cs.completed_at ASC, cs.id ASC
             LIMIT :limit
            """;

    private static final String DONE = COUNTS + """
               AND cs.review_completed_at IS NOT NULL
             ORDER BY cs.review_completed_at DESC, cs.id DESC
             LIMIT :limit
            """;

    /**
     * 후보 없는 세트도 검수를 기다릴 때(app.review.hold-empty, 11/9): 후보 없는 세트를 0건으로 함께 싣는다. 탐지·정합이 아직이면
     * 검수할 수 없으니 대기 목록에서 빼고, 완료 목록에는 후보 없이 완료한 세트도 들어간다.
     */
    private static final String COUNTS_WITH_EMPTY = COUNTS
            .replace("  JOIN per_set ps ON ps.set_id = cs.id", "  LEFT JOIN per_set ps ON ps.set_id = cs.id")
            .replace("ps.pending, ps.confirmed, ps.dismissed",
                    "coalesce(ps.pending, 0) AS pending, coalesce(ps.confirmed, 0) AS confirmed,"
                            + " coalesce(ps.dismissed, 0) AS dismissed");

    private static final String READY = """
               AND (ps.set_id IS NOT NULL OR ((cs.source <> 'BOOTH' OR cs.detections_received_at IS NOT NULL)
                    AND (cs.profile = 'QUICK' OR cs.status = 'INSPECTED')))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean holdEmpty;

    public ReviewQueueRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, false, false);
    }

    @Autowired
    public ReviewQueueRepository(NamedParameterJdbcTemplate jdbc,
            @Value("${app.review.required:true}") boolean reviewRequired,
            @Value("${app.review.hold-empty:true}") boolean holdEmpty) {
        this.jdbc = jdbc;
        this.holdEmpty = reviewRequired && holdEmpty;
    }

    /** 검수 완료 전 시점(오래 기다린 것부터). */
    public List<QueueRow> pending(int limit) {
        return jdbc.query(pendingSql(holdEmpty), new MapSqlParameterSource("limit", limit), (rs, i) -> row(rs));
    }

    /** 검수 완료한 시점(최근 완료부터). */
    public List<QueueRow> done(int limit) {
        return jdbc.query(doneSql(holdEmpty), new MapSqlParameterSource("limit", limit), (rs, i) -> row(rs));
    }

    public static String pendingSql(boolean holdEmpty) {
        return holdEmpty ? COUNTS_WITH_EMPTY + READY + PENDING.substring(COUNTS.length()) : PENDING;
    }

    public static String doneSql(boolean holdEmpty) {
        return holdEmpty ? COUNTS_WITH_EMPTY + DONE.substring(COUNTS.length()) : DONE;
    }

    private static QueueRow row(ResultSet rs) throws SQLException {
        return new QueueRow(rs.getString("checkpoint_id"), rs.getString("rental_id"), rs.getString("public_id"),
                rs.getString("source"), rs.getString("profile"), rs.getString("model_version"), rs.getInt("pending"),
                rs.getInt("confirmed"), rs.getInt("dismissed"), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("review_completed_at", OffsetDateTime.class));
    }

    /** contracts DefectReviewQueueItem 한 행. */
    public record QueueRow(String checkpointId, String rentalId, String captureSetId, String source, String profile,
            String modelVersion, int pending, int confirmed, int dismissed, OffsetDateTime completedAt,
            OffsetDateTime reviewCompletedAt) {
    }
}
