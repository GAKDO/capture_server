package com.gakdo.trace.capture.purge.infra;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.capture.purge.application.PurgeStore;

/**
 * 시점 하나의 행을 FK 순서대로 지운다. 매칭은 다른 시점 흠집을 가리킬 수 있어(prev_defect_id) 양쪽 다 지운다.
 * 시험 렌탈은 렌탈째 지우므로 다른 시점 매칭도 곧 같은 이벤트로 지워진다.
 */
@Repository
public class JdbcPurgeStore implements PurgeStore {

    private static final String SETS = "select id from capture.capture_set where checkpoint_id = ?";
    private static final String PHOTOS = "select id from capture.photo where capture_set_id in (" + SETS + ")";
    private static final String DEFECTS = "select id from inspection.defect where photo_id in (" + PHOTOS + ")";

    private final JdbcTemplate jdbc;

    public JdbcPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean belongsToOtherRental(String rentalId, String checkpointId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from capture.capture_set where checkpoint_id = ? and rental_id <> ?)",
                Boolean.class, checkpointId, rentalId));
    }

    @Override
    public Keys keysOf(String checkpointId) {
        List<String> captures = jdbc.queryForList(
                "select object_key from capture.photo where capture_set_id in (" + SETS + ")", String.class, checkpointId);
        List<String> derived = jdbc.queryForList(
                "select rectified_key from inspection.face_alignment where photo_id in (" + PHOTOS + ")"
                        + " union select object_key from inspection.face_texture where capture_set_id in (" + SETS + ")",
                String.class, checkpointId, checkpointId);
        return new Keys(captures, derived);
    }

    @Override
    public int deleteCheckpoint(String checkpointId) {
        jdbc.update("delete from inspection.defect_match where defect_id in (" + DEFECTS + ") or prev_defect_id in ("
                + DEFECTS + ")", checkpointId, checkpointId);
        jdbc.update("delete from inspection.grade_result where checkpoint_id = ? or capture_set_id in (" + SETS + ")",
                checkpointId, checkpointId);
        jdbc.update("delete from inspection.defect where photo_id in (" + PHOTOS + ")", checkpointId);
        jdbc.update("delete from inspection.face_alignment where photo_id in (" + PHOTOS + ")", checkpointId);
        jdbc.update("delete from inspection.face_texture where capture_set_id in (" + SETS + ")", checkpointId);
        jdbc.update("delete from capture.photo where capture_set_id in (" + SETS + ")", checkpointId);
        return jdbc.update("delete from capture.capture_set where checkpoint_id = ?", checkpointId);
    }
}
