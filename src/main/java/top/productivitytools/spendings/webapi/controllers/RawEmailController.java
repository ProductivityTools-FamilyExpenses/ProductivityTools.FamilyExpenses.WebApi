package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.productivitytools.spendings.webapi.dto.RawEmailRequest;
import top.productivitytools.spendings.webapi.services.EmailProcessingService;

import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class RawEmailController {

    private final JdbcTemplate jdbcTemplate;
    private final EmailProcessingService emailProcessingService;

    @PostMapping("/raw")
    public ResponseEntity<Void> saveRawEmail(@RequestBody RawEmailRequest request) {
        jdbcTemplate.update(
                """
                INSERT INTO raw_emails (
                    message_id, thread_id, source, subject, attachment_name, email_date, raw_html, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'NEW')
                ON CONFLICT (message_id) DO NOTHING
                """,
                request.messageId(),
                Objects.requireNonNullElse(request.threadId(), ""),
                Objects.requireNonNullElse(request.source(), "MBANK"),
                request.subject(),
                request.attachmentName(),
                request.emailDate(),
                request.rawHtml()
        );

        emailProcessingService.triggerAsync();

        return ResponseEntity.accepted().build();
    }

    @PostMapping("/process")
    public ResponseEntity<Map<String, Integer>> processPendingEmails() {
        emailProcessingService.resetErrorEmailsToNew();
        int processed = emailProcessingService.processPendingEmails();
        return ResponseEntity.ok(Map.of("processedEmails", processed));
    }
}
