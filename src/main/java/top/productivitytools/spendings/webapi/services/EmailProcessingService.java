package top.productivitytools.spendings.webapi.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.productivitytools.spendings.webapi.dto.ParsedAccountBalance;
import top.productivitytools.spendings.webapi.dto.ParsedAllegroPurchase;
import top.productivitytools.spendings.webapi.dto.ParsedSpending;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailProcessingService {

    private final JdbcTemplate jdbcTemplate;
    private final MBankEmailParser mBankEmailParser;
    private final AllegroEmailParser allegroEmailParser;
    private final ReentrantLock lock = new ReentrantLock();

    private record RawEmailRow(Long id, String messageId, String source, String rawHtml) {}
    private record AllegroRawEmailRow(Long id, String messageId, OffsetDateTime emailDate, String rawHtml) {}

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
        jdbcTemplate.update(
                """
                UPDATE allegro_raw_emails
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

            while (true) {
                List<AllegroRawEmailRow> pendingAllegroEmails = jdbcTemplate.query(
                        """
                        SELECT id, message_id, email_date, raw_html
                        FROM allegro_raw_emails
                        WHERE status = 'NEW'
                        ORDER BY id ASC
                        LIMIT 50
                        """,
                        (rs, rowNum) -> new AllegroRawEmailRow(
                                rs.getLong("id"),
                                rs.getString("message_id"),
                                rs.getObject("email_date", OffsetDateTime.class),
                                rs.getString("raw_html")
                        )
                );

                if (pendingAllegroEmails.isEmpty()) {
                    break;
                }

                for (AllegroRawEmailRow allegroEmail : pendingAllegroEmails) {
                    processSingleAllegroEmail(allegroEmail);
                    processedCount++;
                }
            }

            enrichSpendingDetails();
        } finally {
            lock.unlock();
        }
        return processedCount;
    }

    public void enrichSpendingDetails() {
        // 1. Utwórz brakujące rekordy 1-1 w spending_details dla każdego wpisu w spendings
        jdbcTemplate.update(
                """
                INSERT INTO spending_details (spending_id)
                SELECT s.id
                FROM spendings s
                LEFT JOIN spending_details sd ON sd.spending_id = s.id
                WHERE sd.spending_id IS NULL
                ON CONFLICT (spending_id) DO NOTHING
                """
        );

        // 2. Uzupełnij konto (account) na podstawie dictionary_account (odpowiednik SetAccount.js)
        jdbcTemplate.update(
                """
                UPDATE spending_details sd
                SET account = matched.account_name,
                    updated_at = NOW()
                FROM (
                    SELECT s.id AS spending_id,
                           COALESCE(da_src.name, da_dst.name) AS account_name
                    FROM spendings s
                    JOIN spending_details sd2 ON sd2.spending_id = s.id
                    LEFT JOIN dictionary_account da_src
                           ON s.src_account IS NOT NULL AND s.src_account <> ''
                          AND (da_src.key = s.src_account OR LTRIM(da_src.key, '0') = LTRIM(s.src_account, '0'))
                    LEFT JOIN dictionary_account da_dst
                           ON s.dst_account IS NOT NULL AND s.dst_account <> ''
                          AND (da_dst.key = s.dst_account OR LTRIM(da_dst.key, '0') = LTRIM(s.dst_account, '0'))
                    WHERE (sd2.account IS NULL OR sd2.account = '')
                      AND COALESCE(da_src.name, da_dst.name) IS NOT NULL
                ) matched
                WHERE sd.spending_id = matched.spending_id
                """
        );

        // 3. Uzupełnij kategorię na podstawie konta (odpowiednik SetAccountAsCategory.js: Wypłaty -> ProxyWypłaty)
        jdbcTemplate.update(
                """
                UPDATE spending_details sd
                SET category = dc.name,
                    updated_at = NOW()
                FROM dictionary_category dc
                WHERE (sd.category IS NULL OR sd.category = '')
                  AND sd.account = 'Wypłaty'
                  AND dc.name = 'ProxyWypłaty'
                """
        );

        // 4. Uzupełnij kategorię na podstawie dictionary_category_mapping (odpowiednik SetCategories.js)
        jdbcTemplate.update(
                """
                UPDATE spending_details sd
                SET category = dcm.category,
                    updated_at = NOW()
                FROM spendings s
                JOIN dictionary_category_mapping dcm ON dcm.key = s.name
                WHERE sd.spending_id = s.id
                  AND (sd.category IS NULL OR sd.category = '')
                """
        );

        // 5. Powiąż wydatki Allegro w spending_details z zamówieniami w allegro_raw_emails po kwocie i zbliżonej dacie (+/- 7 dni)
        linkAllegroPurchasesToSpendings();
    }

    private void linkAllegroPurchasesToSpendings() {
        List<Long> unlinkedSpendingIds = jdbcTemplate.queryForList(
                """
                SELECT s.id
                FROM spendings s
                JOIN spending_details sd ON sd.spending_id = s.id
                WHERE sd.allegro_raw_email_id IS NULL
                  AND s.name IN ('Allegro /Poznan', 'ALLEGRO.PL', 'ALLEGRO.PL &', 'WWW.ALLEGRO.PL')
                ORDER BY s.id ASC
                """,
                Long.class
        );

        for (Long spendingId : unlinkedSpendingIds) {
            jdbcTemplate.update(
                    """
                    UPDATE spending_details sd
                    SET allegro_raw_email_id = matched.allegro_raw_email_id,
                        updated_at = NOW()
                    FROM (
                        SELECT ap.allegro_raw_email_id,
                               MIN(ABS(ap.purchase_date - COALESCE(
                                   CASE
                                       WHEN s.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$' THEN s.operation_date::date
                                       WHEN s.operation_date ~ '^\\d{2}\\.\\d{2}\\.\\d{4}$' THEN TO_DATE(s.operation_date, 'DD.MM.YYYY')
                                       WHEN s.operation_date ~ '^\\d{2}-\\d{2}-\\d{4}$' THEN TO_DATE(s.operation_date, 'DD-MM-YYYY')
                                   END,
                                   (re.email_date AT TIME ZONE 'Europe/Warsaw')::date
                               ))) AS date_diff
                        FROM spendings s
                        JOIN raw_emails re ON re.id = s.raw_email_id
                        JOIN allegro_purchases ap ON ap.full_price = (-1 * s.amount)
                        WHERE s.id = ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM spending_details sd_used
                              WHERE sd_used.allegro_raw_email_id = ap.allegro_raw_email_id
                          )
                          AND ABS(ap.purchase_date - COALESCE(
                              CASE
                                  WHEN s.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$' THEN s.operation_date::date
                                  WHEN s.operation_date ~ '^\\d{2}\\.\\d{2}\\.\\d{4}$' THEN TO_DATE(s.operation_date, 'DD.MM.YYYY')
                                  WHEN s.operation_date ~ '^\\d{2}-\\d{2}-\\d{4}$' THEN TO_DATE(s.operation_date, 'DD-MM-YYYY')
                              END,
                              (re.email_date AT TIME ZONE 'Europe/Warsaw')::date
                          )) <= 7
                        GROUP BY ap.allegro_raw_email_id
                        ORDER BY date_diff ASC, ap.allegro_raw_email_id ASC
                        LIMIT 1
                    ) matched
                    WHERE sd.spending_id = ?
                    """,
                    spendingId,
                    spendingId
            );
        }
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

            List<ParsedAccountBalance> balances = mBankEmailParser.parseAccountBalances(
                    rawEmail.messageId(),
                    rawEmail.rawHtml()
            );

            for (ParsedAccountBalance balance : balances) {
                jdbcTemplate.update(
                        """
                        INSERT INTO account_balances (
                            raw_email_id, operation_id, account, balance_date, operation_time,
                            amount, currency, details
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (operation_id) DO NOTHING
                        """,
                        rawEmail.id(),
                        balance.operationId(),
                        balance.account(),
                        balance.balanceDate(),
                        balance.operationTime(),
                        balance.amount(),
                        balance.currency(),
                        balance.details()
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

    private void processSingleAllegroEmail(AllegroRawEmailRow allegroEmail) {
        try {
            List<ParsedAllegroPurchase> purchases = allegroEmailParser.parseEmailBody(
                    allegroEmail.messageId(),
                    allegroEmail.emailDate(),
                    allegroEmail.rawHtml()
            );

            for (ParsedAllegroPurchase purchase : purchases) {
                jdbcTemplate.update(
                        """
                        INSERT INTO allegro_purchases (
                            allegro_raw_email_id, operation_id, purchase_date, full_price,
                            item_name, item_cost, multiple_items, item_count, item_price
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (operation_id) DO NOTHING
                        """,
                        allegroEmail.id(),
                        purchase.operationId(),
                        purchase.purchaseDate(),
                        purchase.fullPrice(),
                        purchase.itemName(),
                        purchase.itemCost(),
                        purchase.multipleItems(),
                        purchase.itemCount(),
                        purchase.itemPrice()
                );
            }

            jdbcTemplate.update(
                    """
                    UPDATE allegro_raw_emails
                    SET status = 'PROCESSED', processed_at = NOW(), error_message = NULL
                    WHERE id = ?
                    """,
                    allegroEmail.id()
            );
        } catch (Exception ex) {
            log.error("Failed to parse Allegro email id={} messageId={}", allegroEmail.id(), allegroEmail.messageId(), ex);
            jdbcTemplate.update(
                    """
                    UPDATE allegro_raw_emails
                    SET status = 'ERROR', processed_at = NOW(), error_message = ?
                    WHERE id = ?
                    """,
                    ex.getMessage(),
                    allegroEmail.id()
            );
        }
    }
}
