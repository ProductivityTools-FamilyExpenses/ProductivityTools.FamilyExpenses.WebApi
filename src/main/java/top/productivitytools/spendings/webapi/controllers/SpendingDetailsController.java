package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Full details of a single spending: all spendings/spending_details columns, the source e-mail and the linked
 * mBank statement operation. mBank e-mails truncate long titles ("JMP S.A. BIE..."); the statement carries
 * the full title, so it is shown here whenever the spending is linked to a statement operation.
 */
@RestController
@RequestMapping("/api/spendings")
@RequiredArgsConstructor
public class SpendingDetailsController {

    private final JdbcTemplate jdbcTemplate;

    public record StatementOperationInfo(Long id, String operationDate, String title, String rawDescription,
                                         String operationType, String accountName, String srcAccountFull,
                                         String dstAccountFull, String mbankCategory, BigDecimal amount,
                                         String currency, String reconciliationStatus, String matchType) {}

    public record EmailInfo(Long id, String subject, String emailDate, String status) {}

    public record SpendingFullDetails(
            Long id,
            String operationId,
            String operationDate,
            String operationTime,
            String operationType,
            String name,
            /** true when the e-mail title was truncated by mBank (ends with "...") */
            boolean nameTruncated,
            String details,
            String srcAccount,
            String dstAccount,
            BigDecimal amount,
            String currency,
            BigDecimal amountPln,
            BigDecimal fxRate,
            String fxRateDate,
            String amountLeft,
            String amountLeftCurrency,
            String createdAt,
            String category,
            String note,
            String accountKey,
            String accountName,
            Long duplicateOf,
            Long allegroRawEmailId,
            boolean refundExpected,
            Long refundSpendingId,
            /** category rule key that matches this name exactly (dictionary_category_mapping.key), if any */
            String mappingKey,
            String mappingCategory,
            EmailInfo email,
            StatementOperationInfo statementOperation
    ) {}

    @GetMapping("/{id}")
    public SpendingFullDetails getDetails(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT s.id, s.operation_id, s.operation_date, s.operation_time, s.operation_type, s.name, s.details,
                       s.src_account, s.dst_account, s.amount, s.currency, s.amount_pln, s.fx_rate,
                       s.fx_rate_date::text AS fx_rate_date, s.amount_left, s.amount_left_currency,
                       s.created_at::text AS created_at, s.raw_email_id, s.statement_operation_id,
                       sd.category, sd.note, sd.duplicate_of, sd.allegro_raw_email_id,
                       COALESCE(sd.refund_expected, FALSE) AS refund_expected, sd.refund_spending_id,
                       da.key AS account_key, da.name AS account_name,
                       dcm.key AS mapping_key, dcm.category AS mapping_category
                FROM spendings s
                LEFT JOIN spending_details sd ON sd.spending_id = s.id
                LEFT JOIN dictionary_account da ON da.id = sd.account_id
                LEFT JOIN dictionary_category_mapping dcm ON dcm.key = s.name
                WHERE s.id = ?
                """,
                id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transakcja #" + id + " nie istnieje");
        }
        Map<String, Object> r = rows.getFirst();

        EmailInfo email = null;
        Long rawEmailId = toLong(r.get("raw_email_id"));
        if (rawEmailId != null) {
            email = jdbcTemplate.query(
                    "SELECT id, subject, email_date::text AS email_date, status FROM mbank_raw_emails WHERE id = ?",
                    (rs, n) -> new EmailInfo(rs.getLong("id"), rs.getString("subject"), rs.getString("email_date"),
                            rs.getString("status")),
                    rawEmailId).stream().findFirst().orElse(null);
        }

        // Statement operation either created this spending or was linked to it during reconciliation
        StatementOperationInfo statement = jdbcTemplate.query(
                """
                SELECT id, operation_date::text AS operation_date, title, raw_description, operation_type,
                       account_name, src_account_full, dst_account_full, mbank_category, amount, currency,
                       reconciliation_status, match_type
                FROM mbank_statement_operations
                WHERE spending_id = ? OR id = ?
                ORDER BY (spending_id = ?) DESC
                LIMIT 1
                """,
                (rs, n) -> new StatementOperationInfo(rs.getLong("id"), rs.getString("operation_date"),
                        rs.getString("title"), rs.getString("raw_description"), rs.getString("operation_type"),
                        rs.getString("account_name"), rs.getString("src_account_full"),
                        rs.getString("dst_account_full"), rs.getString("mbank_category"),
                        rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getString("reconciliation_status"), rs.getString("match_type")),
                id, toLong(r.get("statement_operation_id")), id).stream().findFirst().orElse(null);

        String name = (String) r.get("name");
        return new SpendingFullDetails(
                toLong(r.get("id")),
                (String) r.get("operation_id"),
                (String) r.get("operation_date"),
                (String) r.get("operation_time"),
                (String) r.get("operation_type"),
                name,
                name != null && name.contains("..."),
                (String) r.get("details"),
                (String) r.get("src_account"),
                (String) r.get("dst_account"),
                (BigDecimal) r.get("amount"),
                (String) r.get("currency"),
                (BigDecimal) r.get("amount_pln"),
                (BigDecimal) r.get("fx_rate"),
                (String) r.get("fx_rate_date"),
                (String) r.get("amount_left"),
                (String) r.get("amount_left_currency"),
                (String) r.get("created_at"),
                (String) r.get("category"),
                (String) r.get("note"),
                (String) r.get("account_key"),
                (String) r.get("account_name"),
                toLong(r.get("duplicate_of")),
                toLong(r.get("allegro_raw_email_id")),
                Boolean.TRUE.equals(r.get("refund_expected")),
                toLong(r.get("refund_spending_id")),
                (String) r.get("mapping_key"),
                (String) r.get("mapping_category"),
                email,
                statement
        );
    }

    private static Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }
}
