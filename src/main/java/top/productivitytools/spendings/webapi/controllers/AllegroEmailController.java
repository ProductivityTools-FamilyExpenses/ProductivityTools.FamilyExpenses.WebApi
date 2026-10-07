package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.productivitytools.spendings.webapi.dto.AllegroRawEmailRequest;
import top.productivitytools.spendings.webapi.services.EmailProcessingService;

import java.util.Objects;

@RestController
@RequestMapping("/api/allegro/emails")
@RequiredArgsConstructor
public class AllegroEmailController {

    private final JdbcTemplate jdbcTemplate;
    private final EmailProcessingService emailProcessingService;

    @PostMapping("/raw")
    public ResponseEntity<Void> saveAllegroRawEmail(@RequestBody AllegroRawEmailRequest request) {
        jdbcTemplate.update(
                """
                INSERT INTO allegro_raw_emails (
                    message_id, thread_id, subject, email_date, raw_html, status
                ) VALUES (?, ?, ?, ?, ?, 'NEW')
                ON CONFLICT (message_id) DO NOTHING
                """,
                request.messageId(),
                Objects.requireNonNullElse(request.threadId(), ""),
                request.subject(),
                request.emailDate(),
                request.rawHtml()
        );

        emailProcessingService.triggerAsync();

        return ResponseEntity.accepted().build();
    }
}
