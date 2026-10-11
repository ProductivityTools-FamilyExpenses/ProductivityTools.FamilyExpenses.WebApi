package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One-off / repeatable import of manual annotations (category, note) kept in the legacy Google Sheet.
 * Rows are matched to spendings primarily by (operation_date, normalised details text) and, as a fallback,
 * by operation_id + date. Idempotent: re-running yields the same state.
 */
@RestController
@RequestMapping("/api/spendings/annotations")
@RequiredArgsConstructor
public class SpendingAnnotationImportController {

    private final JdbcTemplate jdbcTemplate;
    private final top.productivitytools.spendings.webapi.services.EmailProcessingService emailProcessingService;

    public record AnnotationItem(String operationId, String operationDate, String details,
                                 java.math.BigDecimal amount, String name, String category, String note) {}

    public record ImportRequest(List<AnnotationItem> items,
                                /** when true the sheet category replaces an existing one; notes are only filled when empty */
                                Boolean overwriteCategory) {}

    public record UnmatchedItem(String operationId, String operationDate, String category, String note, String reason) {}

    public record ImportResult(int received, Map<String, Integer> matchedBy,
                               int categoriesSet, int notesSet, List<String> createdCategories,
                               List<UnmatchedItem> unmatched) {}

    @PostMapping("/import")
    @Transactional
    public ImportResult importAnnotations(@RequestBody ImportRequest request) {
        boolean overwrite = request.overwriteCategory() == null || request.overwriteCategory();
        List<AnnotationItem> items = request.items() == null ? List.of() : request.items();

        // dictionary_category by lower-case name, so "jedzenie" maps to "Jedzenie"
        Map<String, String> categories = new HashMap<>();
        jdbcTemplate.query("SELECT name FROM dictionary_category", rs -> {
            categories.put(rs.getString("name").toLowerCase(Locale.ROOT), rs.getString("name"));
        });

        Set<Long> used = new HashSet<>();
        List<String> createdCategories = new ArrayList<>();
        List<UnmatchedItem> unmatched = new ArrayList<>();
        Map<String, Integer> matchedBy = new java.util.LinkedHashMap<>();
        int categoriesSet = 0, notesSet = 0;

        for (AnnotationItem item : items) {
            String category = blankToNull(item.category());
            String note = blankToNull(item.note());
            if (category == null && note == null) {
                continue;
            }

            String normalizedDetails = normalize(item.details());
            String messageId = item.operationId() == null || !item.operationId().contains("-")
                    ? null : item.operationId().substring(0, item.operationId().lastIndexOf('-'));

            // Matching strategies, most specific first. The legacy sheet was edited by hand (dates moved to the
            // purchase day, amounts converted/split), so each fallback relaxes one of the edited attributes.
            Long spendingId = null;
            String how = null;
            if (item.operationDate() != null && normalizedDetails != null) {
                spendingId = firstUnused(used, jdbcTemplate.queryForList(
                        "SELECT id FROM spendings WHERE operation_date = ? AND REGEXP_REPLACE(TRIM(details), '\\s+', ' ', 'g') = ? ORDER BY id",
                        Long.class, item.operationDate(), normalizedDetails));
                how = "date+details";
            }
            if (spendingId == null && messageId != null && normalizedDetails != null) {
                spendingId = firstUnused(used, jdbcTemplate.queryForList(
                        "SELECT id FROM spendings WHERE operation_id LIKE ? AND REGEXP_REPLACE(TRIM(details), '\\s+', ' ', 'g') = ? ORDER BY id",
                        Long.class, messageId + "-%", normalizedDetails));
                how = "message+details";
            }
            if (spendingId == null && normalizedDetails != null) {
                List<Long> ids = jdbcTemplate.queryForList(
                        "SELECT id FROM spendings WHERE REGEXP_REPLACE(TRIM(details), '\\s+', ' ', 'g') = ? ORDER BY id",
                        Long.class, normalizedDetails);
                if (ids.size() == 1) {
                    spendingId = firstUnused(used, ids);
                    how = "details-unique";
                }
            }
            if (spendingId == null && item.operationDate() != null && item.amount() != null && item.name() != null) {
                String namePrefix = normalize(item.name());
                if (namePrefix != null) {
                    namePrefix = namePrefix.substring(0, Math.min(10, namePrefix.length()));
                    spendingId = firstUnused(used, jdbcTemplate.queryForList(
                            "SELECT id FROM spendings WHERE operation_date = ? AND amount = ? AND LEFT(REGEXP_REPLACE(TRIM(name), '\\s+', ' ', 'g'), 10) = ? ORDER BY id",
                            Long.class, item.operationDate(), item.amount(), namePrefix));
                    how = "date+amount+name";
                }
            }
            if (spendingId == null && item.operationId() != null && item.operationDate() != null) {
                spendingId = firstUnused(used, jdbcTemplate.queryForList(
                        "SELECT id FROM spendings WHERE operation_id = ? AND operation_date = ?",
                        Long.class, item.operationId(), item.operationDate()));
                how = "operationId+date";
            }
            if (spendingId == null) {
                unmatched.add(new UnmatchedItem(item.operationId(), item.operationDate(), category, note, "no matching spending"));
                continue;
            }
            matchedBy.merge(how, 1, Integer::sum);
            used.add(spendingId);

            jdbcTemplate.update(
                    "INSERT INTO spending_details (spending_id) VALUES (?) ON CONFLICT (spending_id) DO NOTHING", spendingId);

            if (category != null) {
                String resolved = categories.get(category.toLowerCase(Locale.ROOT));
                if (resolved == null) {
                    jdbcTemplate.update("INSERT INTO dictionary_category (name) VALUES (?) ON CONFLICT (name) DO NOTHING", category);
                    categories.put(category.toLowerCase(Locale.ROOT), category);
                    createdCategories.add(category);
                    resolved = category;
                }
                int rows = overwrite
                        ? jdbcTemplate.update(
                                "UPDATE spending_details SET category = ?, updated_at = NOW() WHERE spending_id = ? AND category IS DISTINCT FROM ?",
                                resolved, spendingId, resolved)
                        : jdbcTemplate.update(
                                "UPDATE spending_details SET category = ?, updated_at = NOW() WHERE spending_id = ? AND (category IS NULL OR category = '')",
                                resolved, spendingId);
                categoriesSet += rows;
            }
            if (note != null) {
                notesSet += jdbcTemplate.update(
                        "UPDATE spending_details SET note = ?, updated_at = NOW() WHERE spending_id = ? AND (note IS NULL OR note = '')",
                        note, spendingId);
            }
        }

        // annotations written to a duplicate row must also show on its canonical counterpart (and vice versa)
        int synced = emailProcessingService.syncDuplicateAnnotations();
        matchedBy.put("syncedDuplicatePairs", synced);
        return new ImportResult(items.size(), matchedBy, categoriesSet, notesSet, createdCategories, unmatched);
    }

    private static Long firstUnused(Set<Long> used, List<Long> ids) {
        for (Long id : ids) {
            if (!used.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static String normalize(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().replaceAll("\\s+", " ");
        return n.isEmpty() ? null : n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
