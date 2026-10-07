package top.productivitytools.spendings.webapi.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.productivitytools.spendings.webapi.dto.ParsedSpending;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailProcessingService {

    private final JdbcTemplate jdbcTemplate;
    private final MBankEmailParser mBankEmailParser;
    private final ReentrantLock lock = new ReentrantLock();

    private record RawEmailRow(Long id, String messageId, String source, String rawHtml) {}

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        resetErrorEmailsToNew();
        processPendingEmails();
    }

    @Scheduled(fixedDelay = 60_000)
    public void runScheduled() {
        processPendingEmails();
    }

    @Async
    public void triggerAsync() {
        processPendingEmails();
    }

    public void resetErrorEmailsToNew() {
        jdbcTemplate.update(
                """
                UPDATE raw_emails
                SET status = 'NEW', error_message = NULL
                WHERE status = 'ERROR'
                """
        );
    }

    public int processPendingEmails() {
        if (!lock.tryLock()) {
            return 0;
        }
        int processedCount = 0;
        try {
            while (true) {
                List<RawEmailRow> pendingEmails = jdbcTemplate.query(
                        """
                        SELECT id, message_id, source, raw_html
                        FROM raw_emails
                        WHERE status = 'NEW' AND source = 'MBANK'
                        ORDER BY id ASC
                        LIMIT 50
                        """,
                        (rs, rowNum) -> new RawEmailRow(
                                rs.getLong("id"),
                                rs.getString("message_id"),
                                rs.getString("source"),
                                rs.getString("raw_html")
                        )
                );

                if (pendingEmails.isEmpty()) {
                    break;
                }

                for (RawEmailRow rawEmail : pendingEmails) {
                    processSingleMBankEmail(rawEmail);
                    processedCount++;
                }
            }
        } finally {
            lock.unlock();
        }
        return processedCount;
    }

    private void processSingleMBankEmail(RawEmailRow rawEmail) {
        try {
            List<ParsedSpending> spendings = mBankEmailParser.parseHtmlAttachment(
                    rawEmail.messageId(),
                    rawEmail.rawHtml()
            );

            for (ParsedSpending spending : spendings) {
                jdbcTemplate.update(
                        """
                        INSERT INTO spendings (
                            raw_email_id, operation_id, operation_date, operation_time, operation_type,
                            src_account, dst_account, amount, currency, name,
                            amount_left, amount_left_currency, details
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (operation_id) DO NOTHING
                        """,
                        rawEmail.id(),
                        spending.operationId(),
                        spending.operationDate(),
                        spending.operationTime(),
                        spending.operationType(),
                        spending.srcAccount(),
                        spending.dstAccount(),
                        spending.amount(),
                        spending.currency(),
                        spending.name(),
                        spending.amountLeft(),
                        spending.amountLeftCurrency(),
                        spending.details()
                );
            }

            jdbcTemplate.update(
                    """
                    UPDATE raw_emails
                    SET status = 'PROCESSED', processed_at = NOW(), error_message = NULL
                    WHERE id = ?
                    """,
                    rawEmail.id()
            );
        } catch (Exception ex) {
            log.error("Failed to parse raw email id={} messageId={}", rawEmail.id(), rawEmail.messageId(), ex);
            jdbcTemplate.update(
                    """
                    UPDATE raw_emails
                    SET status = 'ERROR', processed_at = NOW(), error_message = ?
                    WHERE id = ?
                    """,
                    ex.getMessage(),
                    rawEmail.id()
            );
        }
    }
}
