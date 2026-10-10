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
import top.productivitytools.spendings.webapi.dto.MBankRawEmailResponse;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

/** Read-only view of {@code mbank_raw_emails}; the HTML body is only available as a download. */
@RestController
@RequestMapping("/api/mbank-emails")
@RequiredArgsConstructor
public class MBankRawEmailController {

    private final JdbcTemplate jdbcTemplate;

    @GetMapping
    public List<MBankRawEmailResponse> getAll() {
        return jdbcTemplate.query(
                """
                SELECT e.id, e.message_id, e.thread_id, e.source, e.subject, e.attachment_name,
                       e.email_date, e.status, e.error_message, e.created_at, e.processed_at,
                       (SELECT COUNT(*) FROM spendings s WHERE s.raw_email_id = e.id)          AS spendings_count,
                       (SELECT COUNT(*) FROM account_balances b WHERE b.raw_email_id = e.id)   AS balances_count
                FROM mbank_raw_emails e
                ORDER BY e.email_date DESC NULLS LAST, e.id DESC
                """,
                (rs, rowNum) -> new MBankRawEmailResponse(
                        rs.getLong("id"),
                        rs.getString("message_id"),
                        rs.getString("thread_id"),
                        rs.getString("source"),
                        rs.getString("subject"),
                        rs.getString("attachment_name"),
                        rs.getObject("email_date", OffsetDateTime.class),
                        rs.getString("status"),
                        rs.getString("error_message"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("processed_at", OffsetDateTime.class),
                        rs.getInt("spendings_count"),
                        rs.getInt("balances_count")
                )
        );
    }

    /** Returns the stored HTML attachment as a file download. */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        record Body(String attachmentName, String rawHtml) {}
        Body body;
        try {
            body = jdbcTemplate.queryForObject(
                    "SELECT attachment_name, raw_html FROM mbank_raw_emails WHERE id = ?",
                    (rs, rowNum) -> new Body(rs.getString("attachment_name"), rs.getString("raw_html")),
                    id
            );
        } catch (EmptyResultDataAccessException ex) {
            return ResponseEntity.notFound().build();
        }

        String filename = body.attachmentName() != null && !body.attachmentName().isBlank()
                ? body.attachmentName()
                : "mbank-email-" + id + ".html";
        if (!filename.toLowerCase().endsWith(".html") && !filename.toLowerCase().endsWith(".htm")) {
            filename += ".html";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(body.rawHtml().getBytes(StandardCharsets.UTF_8));
    }
}
