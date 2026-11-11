package com.gakdo.trace.capture.inspection.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.capture.common.infra.PublicIds;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService.Detail;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService.Summary;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 공개 보유 기기 목록·상세의 흠집(로그인 없음, contracts listCatalogInspections·getCatalogInspection, 11/8). */
@RestController
@Validated
public class CatalogController {

    private final CatalogQueryService service;

    public CatalogController(CatalogQueryService service) {
        this.service = service;
    }

    public record CatalogInspectionList(List<Summary> items) {
    }

    /** checkpointIds는 쉼표로 이은 값(form, explode=false). Spring이 쉼표로 나눠 목록으로 받는다. */
    @GetMapping("/api/v1/captures/catalog")
    public CatalogInspectionList list(
            @RequestParam @Size(min = 1, max = 100) List<@Pattern(regexp = PublicIds.PATTERN) String> checkpointIds) {
        return new CatalogInspectionList(service.summaries(checkpointIds));
    }

    @GetMapping("/api/v1/captures/catalog/checkpoints/{checkpointId}")
    public Detail detail(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String checkpointId) {
        return service.detail(checkpointId);
    }
}
