package com.gakdo.trace.capture.captureset.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.capture.captureset.application.CaptureSetService;
import com.gakdo.trace.capture.captureset.application.CaptureSetService.RentalProfileView;
import com.gakdo.trace.capture.common.infra.Caller;
import com.gakdo.trace.capture.common.infra.PublicIds;

import jakarta.validation.constraints.Pattern;

/** 렌탈이 써야 하는 검사 방식(contracts getRentalCaptureProfile, 11/13). 부스·폰이 찍기 전에 출고와 같은 방식만 고르게 한다. */
@RestController
@Validated
public class RentalProfileController {

    private final CaptureSetService service;

    public RentalProfileController(CaptureSetService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/captures/rentals/{rentalId}/profile")
    public RentalProfileView profile(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId,
            @RequestParam(required = false) @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return service.rentalProfile(Caller.from(jwt), rentalId, checkpointId);
    }
}
