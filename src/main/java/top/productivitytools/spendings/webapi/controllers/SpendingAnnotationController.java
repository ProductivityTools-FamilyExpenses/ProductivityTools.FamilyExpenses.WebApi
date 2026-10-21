package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Manual category / note edits made inline on the transactions page. */
@RestController
@RequestMapping("/api/spendings")
@RequiredArgsConstructor
public class SpendingAnnotationController {

    private final JdbcTemplate jdbcTemplate;

    public record AnnotationRequest(String category, String note) {}
    public record AnnotationResponse(long spendingId, String category, String note, int rowsUpdated) {}

    /**
     * Sets category and note of one spending (both fields are replaced; blank = cleared).
     * The same values are written to the other half of a duplicate pair (debit ↔ transfer), so the user
     * does not have to annotate both rows. A new category name is added to dictionary_category.
     */
    @PutMapping("/{id}/annotations")
    public AnnotationResponse update(@PathVariable long id, @RequestBody AnnotationRequest request) {
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM spendings WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spending " + id + " not found");
        }
        String category = blankToNull(request.category());
        String note = blankToNull(request.note());

        if (category != null) {
            jdbcTemplate.update("INSERT INTO dictionary_category (name) VALUES (?) ON CONFLICT (name) DO NOTHING", category);
        }

        // the row itself plus its duplicate twin(s) in either direction
        int rows = jdbcTemplate.update(
                """
                INSERT INTO spending_details (spending_id, category, note, updated_at)
                SELECT s.id, ?, ?, NOW()
                FROM spendings s
                WHERE s.id = ?
                   OR s.id IN (SELECT duplicate_of FROM spending_details WHERE spending_id = ? AND duplicate_of IS NOT NULL)
                   OR s.id IN (SELECT spending_id FROM spending_details WHERE duplicate_of = ?)
                ON CONFLICT (spending_id) DO UPDATE
                SET category = EXCLUDED.category, note = EXCLUDED.note, updated_at = NOW()
                """,
                category, note, id, id, id
        );
        return new AnnotationResponse(id, category, note, rows);
    }

    /** Category names for the inline editor's suggestions, alphabetically. */
    @GetMapping("/categories")
    public List<String> categories() {
        return jdbcTemplate.queryForList("SELECT name FROM dictionary_category ORDER BY name", String.class);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
