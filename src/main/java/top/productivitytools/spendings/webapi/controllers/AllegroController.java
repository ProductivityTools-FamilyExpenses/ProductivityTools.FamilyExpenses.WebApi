package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Read-only views of Allegro data: raw e-mails (body only as download) and parsed purchases. */
@RestController
@RequestMapping("/api/allegro")
@RequiredArgsConstructor
public class AllegroController {

    private final JdbcTemplate jdbcTemplate;

    public record AllegroRawEmailResponse(
            Long id, String messageId, String threadId, String subject, OffsetDateTime emailDate,
            String status, String errorMessage, OffsetDateTime createdAt, OffsetDateTime processedAt,
            int purchasesCount, Long linkedSpendingId
    ) {}

    public record AllegroPurchaseResponse(
            Long id, Long allegroRawEmailId, String operationId, LocalDate purchaseDate, BigDecimal fullPrice,
            String itemName, BigDecimal itemCost, String multipleItems, String itemCount, String itemPrice,
            OffsetDateTime createdAt, String emailSubject,
            // spending linked to this e-mail (via spending_details.allegro_raw_email_id), null when not matched
            Long spendingId, String spendingDate, BigDecimal spendingAmount, String spendingName, String spendingCategory
    ) {}

    @GetMapping("/emails")
    public List<AllegroRawEmailResponse> getEmails() {
        return jdbcTemplate.query(
                """
                SELECT e.id, e.message_id, e.thread_id, e.subject, e.email_date, e.status, e.error_message,
                       e.created_at, e.processed_at,
                       (SELECT COUNT(*) FROM allegro_purchases p WHERE p.allegro_raw_email_id = e.id) AS purchases_count,
                       (SELECT sd.spending_id FROM spending_details sd WHERE sd.allegro_raw_email_id = e.id) AS linked_spending_id
                FROM allegro_raw_emails e
                ORDER BY e.email_date DESC NULLS LAST, e.id DESC
                """,
                (rs, n) -> new AllegroRawEmailResponse(
                        rs.getLong("id"),
                        rs.getString("message_id"),
                        rs.getString("thread_id"),
                        rs.getString("subject"),
                        rs.getObject("email_date", OffsetDateTime.class),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("processed_at", OffsetDateTime.class),
                        rs.getInt("purchases_count"),
                        rs.getObject("linked_spending_id", Long.class)
                )
        );
    }

    /** Returns the stored e-mail HTML as a file download. */
    @GetMapping("/emails/{id}/download")
    public ResponseEntity<byte[]> downloadEmail(@PathVariable long id) {
        String html;
        try {
            html = jdbcTemplate.queryForObject(
                    "SELECT raw_html FROM allegro_raw_emails WHERE id = ?", String.class, id);
        } catch (EmptyResultDataAccessException ex) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("allegro-email-" + id + ".html", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(html == null ? new byte[0] : html.getBytes(StandardCharsets.UTF_8));
    }

    public record AllegroRefundResponse(
            Long id, Long allegroRawEmailId, String operationId, LocalDate refundDate, BigDecimal amount,
            BigDecimal originalAmount, boolean partial, String seller, String description, String emailSubject
    ) {}

    @GetMapping("/refunds")
    public List<AllegroRefundResponse> getRefunds() {
        return jdbcTemplate.query(
                """
                SELECT r.id, r.allegro_raw_email_id, r.operation_id, r.refund_date, r.amount, r.original_amount,
                       r.partial, r.seller, r.description, e.subject AS email_subject
                FROM allegro_refunds r
                JOIN allegro_raw_emails e ON e.id = r.allegro_raw_email_id
                ORDER BY r.refund_date DESC, r.id DESC
                """,
                (rs, n) -> new AllegroRefundResponse(
                        rs.getLong("id"),
                        rs.getLong("allegro_raw_email_id"),
                        rs.getString("operation_id"),
                        rs.getObject("refund_date", LocalDate.class),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("original_amount"),
                        rs.getBoolean("partial"),
                        rs.getString("seller"),
                        rs.getString("description"),
                        rs.getString("email_subject")
                )
        );
    }

    @GetMapping("/purchases")
    public List<AllegroPurchaseResponse> getPurchases() {
        return jdbcTemplate.query(
                """
                SELECT p.id, p.allegro_raw_email_id, p.operation_id, p.purchase_date, p.full_price,
                       p.item_name, p.item_cost, p.multiple_items, p.item_count, p.item_price, p.created_at,
                       e.subject AS email_subject,
                       s.id AS spending_id, s.operation_date AS spending_date, s.amount AS spending_amount,
                       s.name AS spending_name, sd.category AS spending_category
                FROM allegro_purchases p
                JOIN allegro_raw_emails e ON e.id = p.allegro_raw_email_id
                LEFT JOIN spending_details sd ON sd.allegro_raw_email_id = e.id
                LEFT JOIN spendings s ON s.id = sd.spending_id
                ORDER BY p.purchase_date DESC, p.id DESC
                """,
                (rs, n) -> new AllegroPurchaseResponse(
                        rs.getLong("id"),
                        rs.getLong("allegro_raw_email_id"),
                        rs.getString("operation_id"),
                        rs.getObject("purchase_date", LocalDate.class),
                        rs.getBigDecimal("full_price"),
                        rs.getString("item_name"),
                        rs.getBigDecimal("item_cost"),
                        rs.getString("multiple_items"),
                        rs.getString("item_count"),
                        rs.getString("item_price"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("email_subject"),
                        rs.getObject("spending_id", Long.class),
                        rs.getString("spending_date"),
                        rs.getBigDecimal("spending_amount"),
                        rs.getString("spending_name"),
                        rs.getString("spending_category")
                )
        );
    }
}
