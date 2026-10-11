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

    public record AnnotationItem(String operationId, String operationDate, String details, String category, String note) {}

    public record ImportRequest(List<AnnotationItem> items,
                                /** when true the sheet category replaces an existing one; notes are only filled when empty */
                                Boolean overwriteCategory) {}

    public record UnmatchedItem(String operationId, String operationDate, String category, String note, String reason) {}

    public record ImportResult(int received, int matchedByDetails, int matchedByOperationId,
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
        int byDetails = 0, byOperationId = 0, categoriesSet = 0, notesSet = 0;

        for (AnnotationItem item : items) {
            String category = blankToNull(item.category());
            String note = blankToNull(item.note());
            if (category == null && note == null) {
                continue;
            }

            Long spendingId = null;
            String normalizedDetails = normalize(item.details());
            if (item.operationDate() != null && normalizedDetails != null) {
                List<Long> ids = jdbcTemplate.queryForList(
                        """
                        SELECT id FROM spendings
                        WHERE operation_date = ?
                          AND REGEXP_REPLACE(TRIM(details), '\\s+', ' ', 'g') = ?
                        ORDER BY id
                        """,
                        Long.class, item.operationDate(), normalizedDetails);
                for (Long id : ids) {
                    if (!used.contains(id)) {
                        spendingId = id;
                        byDetails++;
                        break;
                    }
                }
            }
            if (spendingId == null && item.operationId() != null && item.operationDate() != null) {
                List<Long> ids = jdbcTemplate.queryForList(
                        "SELECT id FROM spendings WHERE operation_id = ? AND operation_date = ?",
                        Long.class, item.operationId(), item.operationDate());
                if (!ids.isEmpty() && !used.contains(ids.get(0))) {
                    spendingId = ids.get(0);
                    byOperationId++;
                }
            }
            if (spendingId == null) {
                unmatched.add(new UnmatchedItem(item.operationId(), item.operationDate(), category, note, "no matching spending"));
                continue;
            }
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

        return new ImportResult(items.size(), byDetails, byOperationId, categoriesSet, notesSet, createdCategories, unmatched);
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
