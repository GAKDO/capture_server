package com.gakdo.trace.capture.benchmark.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 성능 그래프용 읽기 전용 조회(11/6). 세트와 등급을 한 번에 읽어 세트마다 행 하나로 돌려준다.
 * 엔티티가 아닌 여러 표의 칸을 모으는 조회라 JPA 투영 대신 JDBC로 읽는다(timestamptz → OffsetDateTime 변환을 드라이버에 맡김).
 */
@Repository
public class BenchmarkRepository {

    private static final String SQL = """
            SELECT s.public_id, s.checkpoint_id, s.source, s.profile, s.booth_id, s.detection_model_version,
                   (SELECT count(*) FROM capture.photo p WHERE p.capture_set_id = s.id AND p.uploaded) AS photo_count,
                   s.inference_ms, s.created_at, s.completed_at, s.detections_received_at,
                   g.created_at AS graded_at
              FROM capture.capture_set s
              LEFT JOIN inspection.grade_result g ON g.capture_set_id = s.id
             WHERE (CAST(:source AS varchar) IS NULL OR s.source = CAST(:source AS varchar))
             ORDER BY s.created_at DESC, s.id DESC
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BenchmarkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** source가 null이면 모든 출처. 최근 세트부터 limit개. */
    public List<BenchmarkRow> findRecent(String source, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("source", source).addValue("limit", limit);
        return jdbc.query(SQL, params, (rs, i) -> row(rs));
    }

    private static BenchmarkRow row(ResultSet rs) throws SQLException {
        return new BenchmarkRow(rs.getString("public_id"), rs.getString("checkpoint_id"), rs.getString("source"),
                rs.getString("profile"), rs.getString("booth_id"), rs.getString("detection_model_version"),
                rs.getInt("photo_count"), rs.getObject("inference_ms", Integer.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("detections_received_at", OffsetDateTime.class),
                rs.getObject("graded_at", OffsetDateTime.class));
    }

    /** 세트 한 행. 없는 값은 null. */
    public record BenchmarkRow(String captureSetId, String checkpointId, String source, String profile, String boothId,
            String modelVersion, int photoCount, Integer inferenceMs, OffsetDateTime createdAt,
            OffsetDateTime completedAt, OffsetDateTime detectionsReceivedAt, OffsetDateTime gradedAt) {
    }
}
