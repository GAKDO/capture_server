package com.gakdo.trace.capture.ping;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    @GetMapping("/api/v1/captures/ping")
    public Map<String, String> ping() {
        return Map.of("service", "capture-server", "status", "ok");
    }
}
