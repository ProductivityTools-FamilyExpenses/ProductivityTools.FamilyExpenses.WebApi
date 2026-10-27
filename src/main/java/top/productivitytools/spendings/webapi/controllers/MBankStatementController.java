package top.productivitytools.spendings.webapi.controllers;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.productivitytools.spendings.webapi.services.MBankStatementDiffService;
import top.productivitytools.spendings.webapi.services.MBankStatementDiffService.DbSpendingCandidate;
import top.productivitytools.spendings.webapi.services.MBankStatementDiffService.ReconciliationReport;
import top.productivitytools.spendings.webapi.services.MBankStatementDiffService.StatementDiffReport;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mbank-statements")
public class MBankStatementController {

    private final MBankStatementDiffService diffService;

    public MBankStatementController(MBankStatementDiffService diffService) {
        this.diffService = diffService;
    }

    @PostMapping(value = "/diff", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StatementDiffReport> diffMultipart(@RequestParam("file") MultipartFile file) throws IOException {
        String html = decodeStatementBytes(file.getBytes());
        return ResponseEntity.ok(diffService.compareHtml(html));
    }

    @PostMapping(value = "/diff-raw", consumes = {MediaType.TEXT_HTML_VALUE, MediaType.TEXT_PLAIN_VALUE})
    public ResponseEntity<StatementDiffReport> diffRaw(@RequestBody String htmlContent) {
        return ResponseEntity.ok(diffService.compareHtml(htmlContent));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReconciliationReport> importAndReconcile(@RequestParam("file") MultipartFile file) throws IOException {
        String html = decodeStatementBytes(file.getBytes());
        return ResponseEntity.ok(diffService.importAndReconcileHtml(html));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<ReconciliationReport> getReconciliation(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(diffService.getReconciliationView(from, to));
    }

    public record ReconcileRequest(LocalDate from, LocalDate to) {}

    @PostMapping("/reconcile")
    public ResponseEntity<ReconciliationReport> reconcileRange(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestBody(required = false) ReconcileRequest body
    ) {
        LocalDate effectiveFrom = from != null ? from : (body != null ? body.from() : null);
        LocalDate effectiveTo = to != null ? to : (body != null ? body.to() : null);
        return ResponseEntity.ok(diffService.reconcileRange(effectiveFrom, effectiveTo));
    }

    /**
     * {@code categories} optionally maps statement operation id to a user-chosen category
     * that is applied to the created spending.
     */
    public record PromoteRequest(List<Long> ids, List<Long> statementOperationIds, Map<Long, String> categories,
                                 LocalDate from, LocalDate to) {
        public List<Long> effectiveIds() {
            return ids != null && !ids.isEmpty() ? ids : (statementOperationIds != null ? statementOperationIds : List.of());
        }
    }

    @PostMapping("/promote")
    public ResponseEntity<Map<String, Object>> promoteOperations(@RequestBody PromoteRequest request) {
        int promotedCount = diffService.promoteStatementOperations(
                request.effectiveIds(),
                request.categories() != null ? request.categories() : Map.of());
        ReconciliationReport report = diffService.getReconciliationView(request.from(), request.to());
        return ResponseEntity.ok(Map.of(
                "promotedCount", promotedCount,
                "report", report
        ));
    }

    public record LinkRequest(Long statementOperationId, Long spendingId, LocalDate from, LocalDate to) {}

    @PostMapping("/link")
    public ResponseEntity<ReconciliationReport> linkOperation(@RequestBody LinkRequest request) {
        diffService.linkStatementToSpending(request.statementOperationId(), request.spendingId());
        return ResponseEntity.ok(diffService.getReconciliationView(request.from(), request.to()));
    }

    public record UnlinkRequest(Long statementOperationId, LocalDate from, LocalDate to) {}

    @PostMapping("/unlink")
    public ResponseEntity<ReconciliationReport> unlinkOperation(@RequestBody UnlinkRequest request) {
        diffService.unlinkStatementOperation(request.statementOperationId());
        return ResponseEntity.ok(diffService.getReconciliationView(request.from(), request.to()));
    }

    /** Spendings that can be manually linked to the statement operation (closest amount and date first). */
    @GetMapping("/{id}/link-candidates")
    public List<DbSpendingCandidate> linkCandidates(
            @PathVariable("id") Long id,
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "q", required = false) String query
    ) {
        return diffService.findLinkCandidates(id, days, query);
    }

    public record IgnoreRequest(List<Long> ids, List<Long> statementOperationIds, boolean ignored, LocalDate from, LocalDate to) {
        public List<Long> effectiveIds() {
            return ids != null && !ids.isEmpty() ? ids : (statementOperationIds != null ? statementOperationIds : List.of());
        }
    }

    @PostMapping("/ignore")
    public ResponseEntity<ReconciliationReport> ignoreOperations(@RequestBody IgnoreRequest request) {
        diffService.setIgnoredStatus(request.effectiveIds(), request.ignored());
        return ResponseEntity.ok(diffService.getReconciliationView(request.from(), request.to()));
    }

    /**
     * mBank exports declare both UTF-8 and ISO-8859-2 meta tags, though modern exports are UTF-8
     * (sometimes with a UTF-8 BOM). If UTF-8 decoding produces replacement characters, fall back to Windows-1250 / ISO-8859-2.
     */
    private String decodeStatementBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int offset = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        String utf8 = new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8;
        }
        return new String(bytes, offset, bytes.length - offset, Charset.forName("ISO-8859-2"));
    }
}
