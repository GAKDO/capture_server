package com.gakdo.trace.capture.purge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시험 데이터 지우기(trace.checkpoint.purged, 11/4 민서 요청): 시점 하나의 촬영 세트·검사 결과 행을 한 트랜잭션으로 지운다.
 * MinIO 객체는 커밋 뒤 리스너가 지운다(객체가 남아도 화면에는 나오지 않으므로 행 삭제를 먼저 끝낸다). 이미 없으면 그냥 끝(멱등).
 */
@Service
public class CheckpointPurgeService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointPurgeService.class);

    private final PurgeStore store;

    public CheckpointPurgeService(PurgeStore store) {
        this.store = store;
    }

    @Transactional
    public PurgeStore.Keys purge(CheckpointPurgedEvent event) {
        PurgeStore.Keys keys = store.keysOf(event.checkpointId());
        int sets = store.deleteCheckpoint(event.checkpointId());
        log.info("시험 시점 지움 rental={} checkpoint={} 세트={} 사진={}", event.rentalId(), event.checkpointId(), sets,
                keys.captureKeys().size());
        return keys;
    }
}
