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

    /** Subject prefixes of e-mails that are only stored for now (not parsed), each in its own table. */
    private static final String REFUND_PREFIX = "Zwrot";
    private static final String WITHDRAWAL_PREFIX = "Potwierdzenie odstąpienia";

    /**
     * Stores an Allegro e-mail. Purchase notifications go to allegro_raw_emails and are parsed;
     * refunds ("Zwrot ...") and contract withdrawals ("Potwierdzenie odstąpienia ...") go to their own
     * tables untouched - import only, parsing comes later.
     */
    @PostMapping("/raw")
    public ResponseEntity<Void> saveAllegroRawEmail(@RequestBody AllegroRawEmailRequest request) {
        String table = tableFor(request.subject());
        jdbcTemplate.update(
                """
                INSERT INTO %s (
                    message_id, thread_id, subject, email_date, raw_html, status
                ) VALUES (?, ?, ?, ?, ?, 'NEW')
                ON CONFLICT (message_id) DO NOTHING
                """.formatted(table),
                request.messageId(),
                Objects.requireNonNullElse(request.threadId(), ""),
                request.subject(),
                request.emailDate(),
                request.rawHtml()
        );

        if (table.equals("allegro_raw_emails")) {
            emailProcessingService.triggerAsync();
        }

        return ResponseEntity.accepted().build();
    }

    static String tableFor(String subject) {
        String s = subject == null ? "" : subject.strip();
        if (startsWithIgnoreCase(s, REFUND_PREFIX)) {
            return "allegro_refund_emails";
        }
        if (startsWithIgnoreCase(s, WITHDRAWAL_PREFIX)) {
            return "allegro_withdrawal_emails";
        }
        return "allegro_raw_emails";
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
