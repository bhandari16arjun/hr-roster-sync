package com.moveinsync.rostersync.reconciliation;

import com.moveinsync.rostersync.security.CurrentUser;
import com.moveinsync.rostersync.security.TenantUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliations")
public class ReconciliationController {

    private final ReconciliationService service;
    private final ReconciliationRepository runs;

    public ReconciliationController(ReconciliationService service, ReconciliationRepository runs) {
        this.service = service;
        this.runs = runs;
    }

    @GetMapping
    public List<ReconciliationRun> recent(@RequestParam(defaultValue = "20") int limit) {
        return runs.recent(CurrentUser.tenantId(), Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/{id}")
    public ReconciliationRun get(@PathVariable UUID id) {
        return service.get(CurrentUser.tenantId(), id);
    }

    /** Per-record error report: row number, employee id, field and code. No values. */
    @GetMapping("/{id}/errors")
    public List<Map<String, Object>> errors(@PathVariable UUID id, @RequestParam(defaultValue = "200") int limit) {
        service.get(CurrentUser.tenantId(), id); // tenant check
        return runs.errors(id, Math.min(Math.max(limit, 1), 1000));
    }

    /** A sample of the employee IDs that the export appears to be missing (for the operator's decision). */
    @GetMapping("/{id}/missing")
    public Map<String, Object> missing(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        ReconciliationRun run = service.get(CurrentUser.tenantId(), id);
        return Map.of("missingCount", run.missingCount(),
                "sample", runs.missingSample(id, Math.min(Math.max(limit, 1), 500)));
    }

    @PostMapping("/{id}/approve")
    public ReconciliationRun approve(@PathVariable UUID id) {
        TenantUser op = CurrentUser.get();
        return service.approve(op.tenantId(), id, op.getUsername());
    }

    @PostMapping("/{id}/reject")
    public ReconciliationRun reject(@PathVariable UUID id) {
        TenantUser op = CurrentUser.get();
        return service.reject(op.tenantId(), id, op.getUsername());
    }
}
