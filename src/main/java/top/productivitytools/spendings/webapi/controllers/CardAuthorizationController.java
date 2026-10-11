package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.productivitytools.spendings.webapi.dto.CardAuthorizationResponse;
import top.productivitytools.spendings.webapi.services.EmailProcessingService;

import java.time.OffsetDateTime;
import java.util.List;

/** Read-only view of card pre-authorizations plus a manual trigger for linking them to booked debits. */
@RestController
@RequestMapping("/api/card-authorizations")
@RequiredArgsConstructor
public class CardAuthorizationController {

    private final JdbcTemplate jdbcTemplate;
    private final EmailProcessingService emailProcessingService;

    public record LinkResult(int linked, int totalLinked, int totalUnlinked) {}

    @GetMapping
    public List<CardAuthorizationResponse> getAll() {
        return jdbcTemplate.query(
                """
                SELECT ca.id, ca.raw_email_id, ca.operation_id, ca.operation_date, ca.operation_time,
                       ca.card, ca.merchant, ca.amount, ca.currency, ca.amount_left, ca.amount_left_currency,
                       ca.details, ca.created_at, ca.settled_by_spending_id,
                       s.operation_date AS settled_operation_date,
                       s.amount         AS settled_amount,
                       s.currency       AS settled_currency,
                       da.name          AS settled_account,
                       sd.category      AS settled_category
                FROM card_authorizations ca
                LEFT JOIN spendings s ON s.id = ca.settled_by_spending_id
                LEFT JOIN spending_details sd ON sd.spending_id = s.id
                LEFT JOIN dictionary_account da ON da.id = sd.account_id
                ORDER BY ca.operation_date DESC NULLS LAST, ca.operation_time DESC NULLS LAST, ca.id DESC
                """,
                (rs, rowNum) -> new CardAuthorizationResponse(
                        rs.getLong("id"),
                        rs.getLong("raw_email_id"),
                        rs.getString("operation_id"),
                        rs.getString("operation_date"),
                        rs.getString("operation_time"),
                        rs.getString("card"),
                        rs.getString("merchant"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("amount_left"),
                        rs.getString("amount_left_currency"),
                        rs.getString("details"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("settled_by_spending_id", Long.class),
                        rs.getString("settled_operation_date"),
                        rs.getBigDecimal("settled_amount"),
                        rs.getString("settled_currency"),
                        rs.getString("settled_account"),
                        rs.getString("settled_category")
                )
        );
    }

    /** Links authorizations to the debits that settled them (same step as in the processing pipeline). */
    @PostMapping("/link")
    public LinkResult link() {
        int linked = emailProcessingService.linkCardAuthorizationsToSpendings();
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM card_authorizations WHERE settled_by_spending_id IS NOT NULL", Integer.class);
        Integer unlinked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM card_authorizations WHERE settled_by_spending_id IS NULL", Integer.class);
        return new LinkResult(linked, total == null ? 0 : total, unlinked == null ? 0 : unlinked);
    }
}
