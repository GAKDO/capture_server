package com.gakdo.trace.capture.purge.application;

/**
 * trace.checkpoint.purged (contracts events/trace.checkpoint.purged.schema.json 1.0) 중 이 서버가 읽는 필드.
 * rental-server가 시험 렌탈(시리얼 TEST-)을 지울 때 시점마다 하나씩 낸다.
 */
public record CheckpointPurgedEvent(String schemaVersion, String eventId, String rentalId, String checkpointId,
        String checkpointType, String serial) {

    public static final String ROUTING_KEY = "trace.checkpoint.purged";
    public static final String TEST_SERIAL_PREFIX = "TEST-";

    /** 봉투·시리얼이 갖춰졌고 시험 데이터인지. 아니면 지우지 않는다(실제 기록을 잘못 지우지 않게). */
    public boolean isValidTestPurge() {
        return eventId != null && checkpointId != null && checkpointId.matches("^[A-Za-z0-9_-]{21}$")
                && serial != null && serial.startsWith(TEST_SERIAL_PREFIX);
    }
}
