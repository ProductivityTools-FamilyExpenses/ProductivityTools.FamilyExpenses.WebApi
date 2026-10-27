package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Refund tracking on the transactions page: an expense is flagged as awaiting a refund ("Do zwrotu"),
 * and later the incoming refund transaction is linked to it ("Zwrot"). State lives in
 * {@code spending_details.refund_expected} / {@code refund_spending_id}; detail rows are upserted
 * because they are created lazily.
 */
@RestController
@RequestMapping("/api/spendings")
@RequiredArgsConstructor
public class SpendingRefundController {

    private final JdbcTemplate jdbcTemplate;

    public record LinkRequest(Long refundSpendingId) {}

    /**
     * Refund state of one spending.
     *
     * @param refundExpected     the row is an expense flagged as awaiting a refund
     * @param refundSpendingId   for an expense: the linked refund (incoming) spending, null until linked
     * @param refundOfSpendingId for a refund row: the expense it refunds, null when not linked as a refund
     */
    public record RefundState(long spendingId, boolean refundExpected, Long refundSpendingId, Long refundOfSpendingId) {}

    /** Flags the expense as awaiting a refund; an existing link is kept. */
    @PutMapping("/{id}/refund/expect")
    public RefundState expect(@PathVariable long id) {
        requireExists(id);
        jdbcTemplate.update(
                """
                INSERT INTO spending_details (spending_id, refund_expected, updated_at)
                VALUES (?, TRUE, NOW())
                ON CONFLICT (spending_id) DO UPDATE
                SET refund_expected = TRUE, updated_at = NOW()
                """,
                id);
        return state(id);
    }

    /** Clears the flag and the link. */
    @DeleteMapping("/{id}/refund")
    public RefundState clear(@PathVariable long id) {
        requireExists(id);
        jdbcTemplate.update(
                """
                UPDATE spending_details
                SET refund_expected = FALSE, refund_spending_id = NULL, updated_at = NOW()
                WHERE spending_id = ?
                """,
                id);
        return state(id);
    }

    /**
     * Links the refund transaction {@code refundSpendingId} to the expense {@code id}. The expense stays
     * flagged ({@code refund_expected = TRUE}); the UI shows the linked state via {@code refundSpendingId}.
     * The expense's category (when set) is copied to the refund row, replacing its current category,
     * so that category reports net the refund against the expense. 400 when ids are equal or the refund is already linked
     * to another expense.
     */
    @PutMapping("/{id}/refund/link")
    @Transactional
    public RefundState link(@PathVariable long id, @RequestBody LinkRequest request) {
        if (request == null || request.refundSpendingId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "refundSpendingId is required");
        }
        long refundId = request.refundSpendingId();
        if (refundId == id) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A spending cannot be its own refund");
        }
        requireExists(id);
        requireExists(refundId);

        Long linkedTo = jdbcTemplate.query(
                "SELECT spending_id FROM spending_details WHERE refund_spending_id = ? LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null,
                refundId);
        if (linkedTo != null && linkedTo != id) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Spending " + refundId + " is already linked as the refund of spending " + linkedTo);
        }

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO spending_details (spending_id, refund_expected, refund_spending_id, updated_at)
                    VALUES (?, TRUE, ?, NOW())
                    ON CONFLICT (spending_id) DO UPDATE
                    SET refund_expected = TRUE, refund_spending_id = EXCLUDED.refund_spending_id, updated_at = NOW()
                    """,
                    id, refundId);
        } catch (DuplicateKeyException e) {
            // race with the pre-check: the partial unique index on refund_spending_id rejected it
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Spending " + refundId + " is already linked as the refund of another spending", e);
        }

        // the refund always takes over the expense's category, so reports net it against the same category
        String expenseCategory = jdbcTemplate.query(
                "SELECT category FROM spending_details WHERE spending_id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                id);
        if (expenseCategory != null && !expenseCategory.isBlank()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO spending_details (spending_id, category, updated_at)
                    VALUES (?, ?, NOW())
                    ON CONFLICT (spending_id) DO UPDATE
                    SET category = EXCLUDED.category, updated_at = NOW()
                    """,
                    refundId, expenseCategory);
        }
        return state(id);
    }

    private void requireExists(long id) {
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM spendings WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spending " + id + " not found");
        }
    }

    private RefundState state(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT s.id,
                           COALESCE(sd.refund_expected, FALSE) AS refund_expected,
                           sd.refund_spending_id,
                           (SELECT x.spending_id FROM spending_details x WHERE x.refund_spending_id = s.id LIMIT 1) AS refund_of_spending_id
                    FROM spendings s
                    LEFT JOIN spending_details sd ON sd.spending_id = s.id
                    WHERE s.id = ?
                    """,
                    (rs, n) -> new RefundState(
                            rs.getLong("id"),
                            rs.getBoolean("refund_expected"),
                            rs.getObject("refund_spending_id", Long.class),
                            rs.getObject("refund_of_spending_id", Long.class)),
                    id);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spending " + id + " not found", e);
        }
    }
}
