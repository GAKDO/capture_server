package com.gakdo.trace.capture.ping;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    // 배포 워크플로의 연기 시험이 이 값으로 새 이미지가 떴는지 확인한다(이미지 빌드 때 APP_COMMIT=커밋 SHA)
    private final String commit;

    public PingController(@Value("${app.commit:unknown}") String commit) {
        this.commit = commit;
    }

    @GetMapping("/api/v1/captures/ping")
    public Map<String, String> ping() {
        return Map.of("service", "capture-server", "status", "ok", "commit", commit);
    }
}
