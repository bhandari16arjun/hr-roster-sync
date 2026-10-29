package com.moveinsync.rostersync.sync;

import com.moveinsync.rostersync.common.error.BadRequestException;
import com.moveinsync.rostersync.reconciliation.ReconciliationRun;
import com.moveinsync.rostersync.reconciliation.ReconciliationService;
import com.moveinsync.rostersync.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Endpoints used by the client's HR system (role HR_SYSTEM). */
@RestController
@RequestMapping("/api/v1/hr")
public class HrController {

    private final HrIngestService ingest;
    private final ReconciliationService reconciliation;

    public HrController(HrIngestService ingest, ReconciliationService reconciliation) {
        this.ingest = ingest;
        this.reconciliation = reconciliation;
    }

    public record EventBatch(List<HrEventDto> events) {
    }

    /** Near-real-time changes. Always 200 with a per-record report (partial success is normal). */
    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IngestReport events(@RequestBody EventBatch batch) {
        return ingest.ingest(CurrentUser.tenantId(), batch == null ? null : batch.events());
    }

    /** Nightly full export (CSV, multipart field "file"). Runs the reconciliation and returns its report. */
    @PostMapping(value = "/snapshots", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReconciliationRun snapshot(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("EMPTY_FILE", "the uploaded export is empty");
        }
        try (InputStream in = file.getInputStream()) {
            return reconciliation.reconcile(CurrentUser.tenantId(), in);
        }
    }
}
