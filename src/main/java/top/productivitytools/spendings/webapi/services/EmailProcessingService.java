package top.productivitytools.spendings.webapi.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.productivitytools.spendings.webapi.dto.ParsedExpense;

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

    @Scheduled(fixedDelay = 60_000)
    public void runScheduled() {
        processPendingEmails();
    }

    @Async
    public void triggerAsync() {
        processPendingEmails();
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
            List<ParsedExpense> expenses = mBankEmailParser.parseHtmlAttachment(
                    rawEmail.messageId(),
                    rawEmail.rawHtml()
            );

            for (ParsedExpense expense : expenses) {
                jdbcTemplate.update(
                        """
                        INSERT INTO expenses (
                            raw_email_id, operation_id, operation_date, operation_time, operation_type,
                            src_account, dst_account, amount, currency, name,
                            amount_left, amount_left_currency, details
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (operation_id) DO NOTHING
                        """,
                        rawEmail.id(),
                        expense.operationId(),
                        expense.operationDate(),
                        expense.operationTime(),
                        expense.operationType(),
                        expense.srcAccount(),
                        expense.dstAccount(),
                        expense.amount(),
                        expense.currency(),
                        expense.name(),
                        expense.amountLeft(),
                        expense.amountLeftCurrency(),
                        expense.details()
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
