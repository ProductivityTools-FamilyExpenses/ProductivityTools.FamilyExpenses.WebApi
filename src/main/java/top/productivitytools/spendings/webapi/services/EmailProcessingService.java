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
import top.productivitytools.spendings.webapi.dto.ParsedCardAuthorization;
import top.productivitytools.spendings.webapi.dto.ParsedAllegroPurchase;
import top.productivitytools.spendings.webapi.dto.ParsedSpending;

import java.time.LocalDate;
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
    private final NbpExchangeRateService nbpExchangeRateService;
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

    /** Outcome of a manual processing run triggered from the UI. */
    public record ProcessingResult(
            boolean started,
            int resetFromError,
            int processed,
            int remainingNew,
            int remainingError
    ) {}

    /** Resets ERROR e-mails (mBank + Allegro) back to NEW so they are picked up again. Returns the number of rows reset. */
    public int resetErrorEmailsToNew() {
        int reset = jdbcTemplate.update(
                """
                UPDATE mbank_raw_emails
                SET status = 'NEW', error_message = NULL
                WHERE status = 'ERROR'
                """
        );
        reset += jdbcTemplate.update(
                """
                UPDATE allegro_raw_emails
                SET status = 'NEW', error_message = NULL
                WHERE status = 'ERROR'
                """
        );
        return reset;
    }

    /**
     * Manual run (button in the WebApp): re-queues ERROR e-mails, processes everything pending
     * and reports what is left. {@code started=false} means another run was already in progress.
     */
    public ProcessingResult processAllPendingIncludingErrors() {
        int reset = resetErrorEmailsToNew();
        if (!lock.tryLock()) {
            return new ProcessingResult(false, reset, 0, countByStatus("NEW"), countByStatus("ERROR"));
        }
        int processed;
        try {
            processed = processPendingEmailsLocked();
        } finally {
            lock.unlock();
        }
        return new ProcessingResult(true, reset, processed, countByStatus("NEW"), countByStatus("ERROR"));
    }

    private int countByStatus(String status) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT (SELECT COUNT(*) FROM mbank_raw_emails WHERE status = ?)
                     + (SELECT COUNT(*) FROM allegro_raw_emails WHERE status = ?)
                """,
                Integer.class, status, status
        );
        return count == null ? 0 : count;
    }

    public int processPendingEmails() {
        if (!lock.tryLock()) {
            return 0;
        }
        try {
            return processPendingEmailsLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Processing body – caller must hold {@link #lock}. */
    private int processPendingEmailsLocked() {
        int processedCount = 0;
        {
            // mBank e-mails are processed strictly in id order (= e-mail chronology, see spendings.js).
            // An e-mail in ERROR blocks all newer ones: skipping it would give the newer e-mails'
            // spendings lower ids than the failed e-mail's spendings once it is fixed and re-run.
            mbankLoop:
            while (true) {
                Long firstErrorId = jdbcTemplate.queryForObject(
                        "SELECT MIN(id) FROM mbank_raw_emails WHERE status = 'ERROR'",
                        Long.class
                );
                long upperBoundExclusive = firstErrorId != null ? firstErrorId : Long.MAX_VALUE;

                List<RawEmailRow> pendingEmails = jdbcTemplate.query(
                        """
                        SELECT id, message_id, source, raw_html
                        FROM mbank_raw_emails
                        WHERE status = 'NEW' AND source = 'MBANK'
                          AND id < ?
                        ORDER BY id ASC
                        LIMIT 50
                        """,
                        (rs, rowNum) -> new RawEmailRow(
                                rs.getLong("id"),
                                rs.getString("message_id"),
                                rs.getString("source"),
                                rs.getString("raw_html")
                        ),
                        upperBoundExclusive
                );

                if (pendingEmails.isEmpty()) {
                    if (firstErrorId != null) {
                        log.warn("mBank processing blocked: e-mail id={} is in ERROR; newer e-mails wait until it is fixed",
                                firstErrorId);
                    }
                    break;
                }

                for (RawEmailRow rawEmail : pendingEmails) {
                    boolean ok = processSingleMBankEmail(rawEmail);
                    processedCount++;
                    if (!ok) {
                        log.warn("mBank processing stopped at e-mail id={} (ERROR); newer e-mails wait until it is fixed",
                                rawEmail.id());
                        break mbankLoop;
                    }
                }
            }

            // Allegro e-mails: same strict id-order processing as mBank so allegro_purchases ids stay chronological
            allegroLoop:
            while (true) {
                Long firstAllegroErrorId = jdbcTemplate.queryForObject(
                        "SELECT MIN(id) FROM allegro_raw_emails WHERE status = 'ERROR'",
                        Long.class
                );
                long allegroUpperBound = firstAllegroErrorId != null ? firstAllegroErrorId : Long.MAX_VALUE;

                List<AllegroRawEmailRow> pendingAllegroEmails = jdbcTemplate.query(
                        """
                        SELECT id, message_id, email_date, raw_html
                        FROM allegro_raw_emails
                        WHERE status = 'NEW'
                          AND id < ?
                        ORDER BY id ASC
                        LIMIT 50
                        """,
                        (rs, rowNum) -> new AllegroRawEmailRow(
                                rs.getLong("id"),
                                rs.getString("message_id"),
                                rs.getObject("email_date", OffsetDateTime.class),
                                rs.getString("raw_html")
                        ),
                        allegroUpperBound
                );

                if (pendingAllegroEmails.isEmpty()) {
                    if (firstAllegroErrorId != null) {
                        log.warn("Allegro processing blocked: e-mail id={} is in ERROR; newer e-mails wait until it is fixed",
                                firstAllegroErrorId);
                    }
                    break;
                }

                for (AllegroRawEmailRow allegroEmail : pendingAllegroEmails) {
                    boolean ok = processSingleAllegroEmail(allegroEmail);
                    processedCount++;
                    if (!ok) {
                        log.warn("Allegro processing stopped at e-mail id={} (ERROR); newer e-mails wait until it is fixed",
                                allegroEmail.id());
                        break allegroLoop;
                    }
                }
            }

            enrichSpendingDetails();
        }
        return processedCount;
    }

    public void enrichSpendingDetails() {
        ensureSpendingDetailsRows();
        fillAccounts();
        fillCategories();
        // Przelicz kwoty w walutach obcych na PLN po kursie średnim NBP
        fillAmountsPln();
        // Powiąż autoryzacje kart z obciążeniami, które je rozliczyły (zwykle 1-2 dni później)
        linkCardAuthorizationsToSpendings();
        // Oznacz obciążenia będące lustrzanym zapisem przelewu wychodzącego
        markTransferDuplicates();
        // Kategoria/notatka wpisana na jednej połówce pary duplikatów ma obowiązywać na drugiej
        syncDuplicateAnnotations();
        // Powiąż wydatki Allegro w spending_details z zamówieniami w allegro_raw_emails po kwocie i zbliżonej dacie (+/- 7 dni)
        linkAllegroPurchasesToSpendings();
    }

    private record CurrencyRange(String currency, LocalDate from, LocalDate to) {}

    /**
     * Fills {@code spendings.amount_pln} for rows that do not have it yet.
     * PLN rows are copied as-is; other currencies use the NBP table A mid rate of the last business day
     * <em>before</em> the operation date (the convention used in Polish accounting), looked up in the
     * {@code exchange_rates} cache, which is topped up from api.nbp.pl on demand.
     * Rows whose rate is not available (NBP down, date not parseable) stay NULL and are retried next time.
     * Returns the number of rows converted.
     */
    public int fillAmountsPln() {
        int updated = jdbcTemplate.update(
                """
                UPDATE spendings
                SET amount_pln = amount, fx_rate = 1, fx_rate_date = NULL
                WHERE amount_pln IS NULL AND (currency = 'PLN' OR currency IS NULL OR currency = '')
                """
        );

        // Which (currency, date range) do we still need rates for?
        List<CurrencyRange> ranges = jdbcTemplate.query(
                """
                SELECT currency, MIN(operation_date::date) AS from_date, MAX(operation_date::date) AS to_date
                FROM spendings
                WHERE amount_pln IS NULL
                  AND currency IS NOT NULL AND currency <> '' AND currency <> 'PLN'
                  AND operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$'
                GROUP BY currency
                """,
                (rs, n) -> new CurrencyRange(
                        rs.getString("currency"),
                        rs.getObject("from_date", LocalDate.class),
                        rs.getObject("to_date", LocalDate.class))
        );
        for (CurrencyRange r : ranges) {
            // D-1 rule: the rate may be up to ~10 days older than the operation (holidays), so start the cache earlier
            nbpExchangeRateService.ensureRates(r.currency(), r.from().minusDays(10), r.to());
        }

        updated += jdbcTemplate.update(
                """
                UPDATE spendings s
                SET amount_pln = ROUND(s.amount * m.rate, 2),
                    fx_rate = m.rate,
                    fx_rate_date = m.rate_date
                FROM (
                    SELECT sp.id AS spending_id, x.rate, x.rate_date
                    FROM spendings sp
                    JOIN LATERAL (
                        SELECT rate, rate_date
                        FROM exchange_rates er
                        WHERE er.currency = sp.currency
                          AND er.rate_date < sp.operation_date::date
                        ORDER BY er.rate_date DESC
                        LIMIT 1
                    ) x ON TRUE
                    WHERE sp.amount_pln IS NULL
                      AND sp.currency IS NOT NULL AND sp.currency <> '' AND sp.currency <> 'PLN'
                      AND sp.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$'
                      AND sp.operation_date::date - x.rate_date <= 10
                ) m
                WHERE s.id = m.spending_id
                """
        );
        return updated;
    }

    /**
     * For ~15% of outgoing transfers mBank also sends an "Obciazenie" with the same amount, the same account,
     * the same day or up to a few days earlier (weekend order -> Monday execution) and the transfer <em>title</em> as its name
     * ("... dla KACZMAREK .; PRZELEW SRODKOW;" ↔ "tytulem: PRZELEW SRODKOW"), or "P.SEPA WYCH." for SEPA.
     * Such debits are marked as duplicates of the transfer (which is the richer record) and excluded from sums.
     * Idempotent and incremental: only unmarked debits are considered, each transfer is used at most once.
     * Returns the number of newly marked debits.
     */
    public int markTransferDuplicates() {
        ensureSpendingDetailsRows();
        int total = 0;
        for (int pass = 0; pass < 10; pass++) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE spending_details sd
                    SET duplicate_of = m.transfer_id,
                        updated_at = NOW()
                    FROM (
                        SELECT DISTINCT ON (transfer_id) debit_id, transfer_id
                        FROM (
                            SELECT DISTINCT ON (d.id) d.id AS debit_id, t.id AS transfer_id
                            FROM spendings d
                            JOIN spending_details dd ON dd.spending_id = d.id
                            JOIN spendings t
                              ON t.operation_type = 'Przelew wychodzacy'
                             AND t.src_account = d.src_account
                             AND t.amount = d.amount
                             AND t.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$'
                             AND d.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$'
                             -- the debit is booked when the transfer is ordered; the transfer itself is executed in the
                             -- next Elixir session (same day, or Monday 06:xx for weekend orders -> up to 3-4 days later)
                             AND t.operation_date::date BETWEEN d.operation_date::date AND d.operation_date::date + 4
                             AND (
                                   UPPER(d.name) LIKE 'P.SEPA%'
                                   OR (
                                        LENGTH(REGEXP_REPLACE(UPPER(REPLACE(d.name, '...', '')), '[^A-Z0-9]', '', 'g')) >= 3
                                    AND LENGTH(REGEXP_REPLACE(UPPER(SPLIT_PART(t.name, ';', 2)), '[^A-Z0-9]', '', 'g')) >= 3
                                    AND LEFT(REGEXP_REPLACE(UPPER(REPLACE(d.name, '...', '')), '[^A-Z0-9]', '', 'g'), 6)
                                      = LEFT(REGEXP_REPLACE(UPPER(SPLIT_PART(t.name, ';', 2)), '[^A-Z0-9]', '', 'g'), 6)
                                   )
                                 )
                             AND NOT EXISTS (SELECT 1 FROM spending_details used WHERE used.duplicate_of = t.id)
                            WHERE d.operation_type = 'Obciazenie'
                              AND dd.duplicate_of IS NULL
                            ORDER BY d.id,
                                     t.operation_date::date - d.operation_date::date,
                                     CASE WHEN t.operation_time = d.operation_time THEN 0 ELSE 1 END,
                                     t.id
                        ) best_per_debit
                        ORDER BY transfer_id, debit_id
                    ) m
                    WHERE sd.spending_id = m.debit_id
                    """
            );
            total += updated;
            if (updated == 0) {
                break;
            }
        }
        return total;
    }

    /**
     * Within a duplicate pair (debit -> transfer) the user may have annotated either row (the legacy sheet
     * categorised the debit, the UI shows the transfer). Copies category and note to whichever side is empty,
     * so reports (which use the canonical row) and the hidden duplicate agree. Returns rows updated.
     */
    public int syncDuplicateAnnotations() {
        int updated = 0;
        // duplicate -> canonical
        updated += jdbcTemplate.update(
                """
                UPDATE spending_details canon
                SET category = COALESCE(NULLIF(canon.category, ''), dup.category),
                    note = COALESCE(NULLIF(canon.note, ''), dup.note),
                    updated_at = NOW()
                FROM spending_details dup
                WHERE dup.duplicate_of = canon.spending_id
                  AND (
                        ((canon.category IS NULL OR canon.category = '') AND dup.category IS NOT NULL AND dup.category <> '')
                     OR ((canon.note IS NULL OR canon.note = '') AND dup.note IS NOT NULL AND dup.note <> '')
                  )
                """
        );
        // canonical -> duplicate
        updated += jdbcTemplate.update(
                """
                UPDATE spending_details dup
                SET category = COALESCE(NULLIF(dup.category, ''), canon.category),
                    note = COALESCE(NULLIF(dup.note, ''), canon.note),
                    updated_at = NOW()
                FROM spending_details canon
                WHERE dup.duplicate_of = canon.spending_id
                  AND (
                        ((dup.category IS NULL OR dup.category = '') AND canon.category IS NOT NULL AND canon.category <> '')
                     OR ((dup.note IS NULL OR dup.note = '') AND canon.note IS NOT NULL AND canon.note <> '')
                  )
                """
        );
        return updated;
    }

    /**
     * Matches card authorizations with the "Obciazenie" that booked them: same (negative) amount,
     * booking 0-7 days after the authorization, merchant name sharing the first 6 significant characters
     * (the debit title is truncated, e.g. "ZABKA Z7239 ..." vs "ZABKA Z7239 K.2 WARSZAWA").
     * Foreign-currency authorizations are booked in PLN with a different amount and stay unlinked.
     * Returns the number of newly linked authorizations.
     */
    public int linkCardAuthorizationsToSpendings() {
        int total = 0;
        // Each pass assigns every debit at most once; repeat for authorizations that lost the tie.
        for (int pass = 0; pass < 10; pass++) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE card_authorizations ca
                    SET settled_by_spending_id = m.spending_id
                    FROM (
                        SELECT DISTINCT ON (spending_id) auth_id, spending_id
                        FROM (
                            SELECT DISTINCT ON (ca2.id) ca2.id AS auth_id, s.id AS spending_id
                            FROM card_authorizations ca2
                            JOIN spendings s
                              ON s.operation_type = 'Obciazenie'
                             AND s.amount = ca2.amount
                             AND s.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$'
                             AND ca2.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$'
                             AND s.operation_date::date BETWEEN ca2.operation_date::date AND ca2.operation_date::date + 7
                             AND LEFT(REGEXP_REPLACE(UPPER(s.name), '[^A-Z0-9]', '', 'g'), 6)
                               = LEFT(REGEXP_REPLACE(UPPER(ca2.merchant), '[^A-Z0-9]', '', 'g'), 6)
                             AND LENGTH(REGEXP_REPLACE(UPPER(ca2.merchant), '[^A-Z0-9]', '', 'g')) >= 6
                             AND NOT EXISTS (SELECT 1 FROM card_authorizations used WHERE used.settled_by_spending_id = s.id)
                            WHERE ca2.settled_by_spending_id IS NULL
                            ORDER BY ca2.id, s.operation_date, s.operation_time, s.id
                        ) best_per_auth
                        ORDER BY spending_id, auth_id
                    ) m
                    WHERE ca.id = m.auth_id
                    """
            );
            total += updated;
            if (updated == 0) {
                break;
            }
        }
        return total;
    }

    /** Tworzy brakujące rekordy 1-1 w spending_details dla każdego wpisu w spendings. */
    private void ensureSpendingDetailsRows() {
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
    }

    /**
     * Uzupełnia konto (account_id -> dictionary_account) na podstawie numeru rachunku/karty
     * (odpowiednik SetAccount.js) dla wierszy, które jeszcze go nie mają. Zwraca liczbę uzupełnionych wierszy.
     */
    public int fillAccounts() {
        ensureSpendingDetailsRows();
        return jdbcTemplate.update(
                """
                UPDATE spending_details sd
                SET account_id = matched.account_id,
                    updated_at = NOW()
                FROM (
                    SELECT s.id AS spending_id,
                           COALESCE(da_src.id, da_dst.id) AS account_id
                    FROM spendings s
                    JOIN spending_details sd2 ON sd2.spending_id = s.id
                    LEFT JOIN dictionary_account da_src
                           ON s.src_account IS NOT NULL AND s.src_account <> ''
                          AND (da_src.key = s.src_account OR LTRIM(da_src.key, '0') = LTRIM(s.src_account, '0'))
                    LEFT JOIN dictionary_account da_dst
                           ON s.dst_account IS NOT NULL AND s.dst_account <> ''
                          AND (da_dst.key = s.dst_account OR LTRIM(da_dst.key, '0') = LTRIM(s.dst_account, '0'))
                    WHERE sd2.account_id IS NULL
                      AND COALESCE(da_src.id, da_dst.id) IS NOT NULL
                ) matched
                WHERE sd.spending_id = matched.spending_id
                """
        );
    }

    /**
     * Uzupełnia kategorię dla wierszy bez kategorii: najpierw na podstawie konta
     * (odpowiednik SetAccountAsCategory.js: Wypłaty -> ProxyWypłaty), potem z dictionary_category_mapping
     * po nazwie operacji (odpowiednik SetCategories.js). Zwraca liczbę uzupełnionych wierszy.
     */
    public int fillCategories() {
        ensureSpendingDetailsRows();
        int updated = jdbcTemplate.update(
                """
                UPDATE spending_details sd
                SET category = dc.name,
                    updated_at = NOW()
                FROM dictionary_category dc, dictionary_account da
                WHERE (sd.category IS NULL OR sd.category = '')
                  AND da.id = sd.account_id
                  AND da.name = 'Wypłaty'
                  AND dc.name = 'ProxyWypłaty'
                """
        );
        updated += jdbcTemplate.update(
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
        return updated;
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
                        JOIN mbank_raw_emails re ON re.id = s.raw_email_id
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

    private boolean processSingleMBankEmail(RawEmailRow rawEmail) {
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

            List<ParsedCardAuthorization> authorizations = mBankEmailParser.parseCardAuthorizations(
                    rawEmail.messageId(),
                    rawEmail.rawHtml()
            );

            for (ParsedCardAuthorization auth : authorizations) {
                jdbcTemplate.update(
                        """
                        INSERT INTO card_authorizations (
                            raw_email_id, operation_id, operation_date, operation_time, card, merchant,
                            amount, currency, amount_left, amount_left_currency, details
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (operation_id) DO NOTHING
                        """,
                        rawEmail.id(),
                        auth.operationId(),
                        auth.operationDate(),
                        auth.operationTime(),
                        auth.card(),
                        auth.merchant(),
                        auth.amount(),
                        auth.currency(),
                        auth.amountLeft(),
                        auth.amountLeftCurrency(),
                        auth.details()
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
                    UPDATE mbank_raw_emails
                    SET status = 'PROCESSED', processed_at = NOW(), error_message = NULL
                    WHERE id = ?
                    """,
                    rawEmail.id()
            );
            return true;
        } catch (Exception ex) {
            log.error("Failed to parse raw email id={} messageId={}", rawEmail.id(), rawEmail.messageId(), ex);
            jdbcTemplate.update(
                    """
                    UPDATE mbank_raw_emails
                    SET status = 'ERROR', processed_at = NOW(), error_message = ?
                    WHERE id = ?
                    """,
                    ex.getMessage(),
                    rawEmail.id()
            );
            return false;
        }
    }

    private boolean processSingleAllegroEmail(AllegroRawEmailRow allegroEmail) {
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
            return true;
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
            return false;
        }
    }
}
