package top.productivitytools.spendings.webapi.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.productivitytools.spendings.webapi.services.MBankStatementParser.ParsedStatement;
import top.productivitytools.spendings.webapi.services.MBankStatementParser.StatementAccount;
import top.productivitytools.spendings.webapi.services.MBankStatementParser.StatementOperation;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class MBankStatementDiffService {

    private final JdbcTemplate jdbcTemplate;
    private final MBankStatementParser statementParser;
    private final EmailProcessingService emailProcessingService;

    public MBankStatementDiffService(
            JdbcTemplate jdbcTemplate,
            MBankStatementParser statementParser,
            EmailProcessingService emailProcessingService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.statementParser = statementParser;
        this.emailProcessingService = emailProcessingService;
    }

    public record DbSpendingCandidate(
            Long id,
            LocalDate effectiveDate,
            String rawOperationDate,
            String title,
            String operationType,
            BigDecimal amount,
            String currency,
            BigDecimal amountPln,
            String srcAccount,
            String dstAccount,
            String categoryName,
            String accountKey,
            Long rawEmailId,
            Long statementOperationId
    ) {}

    public record MatchedPairDto(
            StatementOperation statementOperation,
            DbSpendingCandidate dbSpending,
            String matchType,
            long dateDiffDays,
            int score
    ) {}

    public record StatementDiffReport(
            String periodFrom,
            String periodTo,
            int statementCount,
            int dbCountInPeriod,
            int matchedExactCount,
            int matchedDateShiftCount,
            int missingInDbCount,
            int onlyInDbCount,
            BigDecimal statementTotalAmount,
            BigDecimal dbTotalAmount,
            BigDecimal missingInDbTotalAmount,
            BigDecimal onlyInDbTotalAmount,
            List<StatementAccount> statementAccounts,
            List<StatementOperation> missingInDb,
            List<MatchedPairDto> matchedDateShift,
            List<MatchedPairDto> matchedExact,
            List<DbSpendingCandidate> onlyInDb
    ) {}

    public record StoredStatementOperationDto(
            Long id,
            String rowFingerprint,
            String operationDate,
            int sameDaySequence,
            String title,
            String operationType,
            String rawDescription,
            String accountName,
            String srcAccountShort,
            String srcAccountFull,
            String dstAccountFull,
            String dstAccountShort,
            String mbankCategory,
            BigDecimal amount,
            String currency,
            String reconciliationStatus,
            String matchType,
            Integer dateDiffDays,
            Integer matchScore,
            Long spendingId,
            Long candidateSpendingId,
            DbSpendingCandidate linkedSpending,
            DbSpendingCandidate candidateSpending
    ) {}

    public record ReconciliationReport(
            String periodFrom,
            String periodTo,
            int totalStatementOperations,
            int newlyInsertedCount,
            int alreadyExistedCount,
            int unReconciledCount,
            int candidateDateShiftCount,
            int matchedEmailCount,
            int importedToSpendingsCount,
            int ignoredCount,
            int onlyInDbCount,
            BigDecimal unReconciledTotalAmount,
            BigDecimal candidateDateShiftTotalAmount,
            BigDecimal matchedEmailTotalAmount,
            BigDecimal importedToSpendingsTotalAmount,
            BigDecimal onlyInDbTotalAmount,
            List<StoredStatementOperationDto> unReconciled,
            List<StoredStatementOperationDto> candidateDateShift,
            List<StoredStatementOperationDto> matchedEmail,
            List<StoredStatementOperationDto> importedToSpendings,
            List<StoredStatementOperationDto> ignored,
            List<DbSpendingCandidate> onlyInDb
    ) {}

    /**
     * Persistent import of mBank HTML statement into mbank_statement_operations,
     * followed by automatic reconciliation and returning the full ReconciliationReport.
     */
    @Transactional
    public ReconciliationReport importAndReconcileHtml(String htmlContent) {
        ParsedStatement parsed = statementParser.parse(htmlContent);
        List<StatementOperation> stmtOps = parsed.operations();

        LocalDate fromDate = parsed.fromDate();
        LocalDate toDate = parsed.toDate();
        for (StatementOperation op : stmtOps) {
            LocalDate opDate = parseIsoDate(op.operationDate());
            if (opDate != null) {
                if (fromDate == null || opDate.isBefore(fromDate)) {
                    fromDate = opDate;
                }
                if (toDate == null || opDate.isAfter(toDate)) {
                    toDate = opDate;
                }
            }
        }
        if (fromDate == null) {
            fromDate = LocalDate.now().minusYears(1);
        }
        if (toDate == null) {
            toDate = LocalDate.now();
        }

        LocalDate parsedPeriodFrom = parsed.fromDate();
        LocalDate parsedPeriodTo = parsed.toDate();

        // Pre-compute same-day sequence numbers and fingerprints
        Map<String, Integer> counterByBaseKey = new HashMap<>();
        int newlyInserted = 0;
        int alreadyExisted = 0;

        for (StatementOperation op : stmtOps) {
            LocalDate opDate = parseIsoDate(op.operationDate());
            if (opDate == null || op.amount() == null) {
                continue;
            }
            String srcShort = normalizeShortAccount(op.accountIban(), op.accountShort8());
            String dstShort = normalizeShortAccount(op.dstAccount(), null);
            String normDesc = normalizeDescriptionForFingerprint(op.rawDescription());
            String baseKey = opDate + "|"
                    + (srcShort != null ? srcShort : "") + "|"
                    + op.amount().stripTrailingZeros().toPlainString() + "|"
                    + (op.currency() != null ? op.currency().toUpperCase(Locale.ROOT) : "PLN") + "|"
                    + normDesc;

            int seq = counterByBaseKey.merge(baseKey, 1, Integer::sum);
            String fingerprint = sha256Hex(baseKey + "|#" + seq);

            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO mbank_statement_operations (
                        row_fingerprint, operation_date, same_day_sequence,
                        title, operation_type, raw_description,
                        account_name, src_account_short, src_account_full,
                        dst_account_full, dst_account_short, mbank_category,
                        amount, currency, statement_period_from, statement_period_to,
                        reconciliation_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNRECONCILED')
                    ON CONFLICT (row_fingerprint) DO NOTHING
                    """,
                    fingerprint,
                    Date.valueOf(opDate),
                    seq,
                    op.title(),
                    op.operationType(),
                    op.rawDescription(),
                    op.accountName(),
                    srcShort,
                    op.accountIban(),
                    op.dstAccount(),
                    dstShort,
                    op.mbankCategory(),
                    op.amount(),
                    op.currency() != null ? op.currency() : "PLN",
                    parsedPeriodFrom != null ? Date.valueOf(parsedPeriodFrom) : null,
                    parsedPeriodTo != null ? Date.valueOf(parsedPeriodTo) : null
            );

            if (inserted > 0) {
                newlyInserted++;
            } else {
                alreadyExisted++;
            }
        }

        runReconciliationInRange(fromDate, toDate);
        return buildReconciliationReport(fromDate, toDate, newlyInserted, alreadyExisted);
    }

    private LocalDate parseIsoDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public ReconciliationReport reconcileRange(LocalDate from, LocalDate to) {
        LocalDate[] range = resolveDateRange(from, to);
        runReconciliationInRange(range[0], range[1]);
        return buildReconciliationReport(range[0], range[1], 0, 0);
    }

    public ReconciliationReport getReconciliationView(LocalDate from, LocalDate to) {
        LocalDate[] range = resolveDateRange(from, to);
        return buildReconciliationReport(range[0], range[1], 0, 0);
    }

    private LocalDate[] resolveDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return new LocalDate[]{from, to};
        }
        return jdbcTemplate.query(
                """
                SELECT MIN(operation_date) AS min_date, MAX(operation_date) AS max_date
                FROM mbank_statement_operations
                """,
                rs -> {
                    if (rs.next()) {
                        Date minD = rs.getDate("min_date");
                        Date maxD = rs.getDate("max_date");
                        LocalDate f = from != null ? from : (minD != null ? minD.toLocalDate() : LocalDate.now().withDayOfYear(1));
                        LocalDate t = to != null ? to : (maxD != null ? maxD.toLocalDate() : LocalDate.now());
                        return new LocalDate[]{f, t};
                    }
                    return new LocalDate[]{
                            from != null ? from : LocalDate.now().withDayOfYear(1),
                            to != null ? to : LocalDate.now()
                    };
                }
        );
    }

    /**
     * Reconciles UNRECONCILED and CANDIDATE_DATE_SHIFT statement operations in [fromDate, toDate]
     * against spendings in [fromDate - 4 days, toDate + 4 days].
     */
    @Transactional
    public void runReconciliationInRange(LocalDate fromDate, LocalDate toDate) {
        // First reset any CANDIDATE_DATE_SHIFT whose candidate spending no longer exists or is already linked,
        // or re-evaluate UNRECONCILED + CANDIDATE_DATE_SHIFT rows in the range.
        jdbcTemplate.update(
                """
                UPDATE mbank_statement_operations
                SET reconciliation_status = 'UNRECONCILED',
                    match_type = NULL,
                    date_diff_days = NULL,
                    match_score = NULL,
                    candidate_spending_id = NULL
                WHERE operation_date >= ? AND operation_date <= ?
                  AND reconciliation_status = 'CANDIDATE_DATE_SHIFT'
                """,
                Date.valueOf(fromDate),
                Date.valueOf(toDate)
        );

        // Also ensure any statement row that already has spending_id set via spendings.statement_operation_id is synced
        jdbcTemplate.update(
                """
                UPDATE mbank_statement_operations mso
                SET spending_id = s.id,
                    reconciliation_status = CASE
                        WHEN s.raw_email_id IS NOT NULL THEN 'MATCHED_EMAIL'
                        ELSE 'IMPORTED_TO_SPENDINGS'
                    END,
                    reconciled_at = COALESCE(mso.reconciled_at, NOW())
                FROM spendings s
                WHERE s.statement_operation_id = mso.id
                  AND mso.spending_id IS NULL
                """
        );

        List<StoredStatementOpRow> pendingOps = fetchPendingStatementOps(fromDate, toDate);
        if (pendingOps.isEmpty()) {
            return;
        }

        // Wide enough for pass 3 (title-confirmed matches up to LONG_SHIFT_MAX_DAYS apart)
        LocalDate dbQueryFrom = fromDate.minusDays(LONG_SHIFT_MAX_DAYS + 1);
        LocalDate dbQueryTo = toDate.plusDays(LONG_SHIFT_MAX_DAYS + 1);
        List<DbSpendingCandidate> allDbCandidates = fetchDbCandidates(dbQueryFrom, dbQueryTo);

        // Exclude DB spendings that are already linked to any mbank_statement_operations row
        Set<Long> alreadyLinkedSpendingIds = new HashSet<>(
                jdbcTemplate.queryForList(
                        "SELECT spending_id FROM mbank_statement_operations WHERE spending_id IS NOT NULL",
                        Long.class
                )
        );

        List<DbSpendingCandidate> availableDb = allDbCandidates.stream()
                .filter(db -> !alreadyLinkedSpendingIds.contains(db.id()) && db.statementOperationId() == null)
                .toList();

        Set<Long> matchedStmtIds = new HashSet<>();
        Set<Long> matchedDbIds = new HashSet<>();

        // Pass 1: Exact date + exact amount + same src_account (or compatible) -> MATCHED_EMAIL
        List<PotentialStoredMatch> pass1 = new ArrayList<>();
        for (StoredStatementOpRow stmtOp : pendingOps) {
            for (DbSpendingCandidate db : availableDb) {
                if (!amountsEqual(stmtOp.amount(), db.amount())) {
                    continue;
                }
                if (!currenciesCompatible(stmtOp.currency(), db.currency())) {
                    continue;
                }
                if (!Objects.equals(stmtOp.operationDate(), db.effectiveDate())) {
                    continue;
                }
                if (!accountsCompatible(stmtOp.srcAccountShort(), db)) {
                    continue;
                }
                int score = scoreStoredPair(stmtOp, db, 0);
                pass1.add(new PotentialStoredMatch(stmtOp.id(), db.id(), "EXACT", 0, score));
            }
        }
        pass1.sort(Comparator.comparingInt(PotentialStoredMatch::score).reversed());

        for (PotentialStoredMatch m : pass1) {
            if (matchedStmtIds.contains(m.stmtId()) || matchedDbIds.contains(m.dbId())) {
                continue;
            }
            matchedStmtIds.add(m.stmtId());
            matchedDbIds.add(m.dbId());
            jdbcTemplate.update(
                    """
                    UPDATE mbank_statement_operations
                    SET reconciliation_status = 'MATCHED_EMAIL',
                        match_type = ?,
                        date_diff_days = 0,
                        match_score = ?,
                        spending_id = ?,
                        candidate_spending_id = NULL,
                        reconciled_at = NOW()
                    WHERE id = ?
                    """,
                    m.matchType(),
                    m.score(),
                    m.dbId(),
                    m.stmtId()
            );
        }

        // Pass 2: Date shift up to 3 days + exact amount + compatible account
        // High confidence (±1 day + same src_account + title/dst match >= 115) -> auto-link as MATCHED_EMAIL (DATE_SHIFT_1D)
        // Otherwise -> mark as CANDIDATE_DATE_SHIFT with candidate_spending_id for 1-click user review
        List<PotentialStoredMatch> pass2 = new ArrayList<>();
        for (StoredStatementOpRow stmtOp : pendingOps) {
            if (matchedStmtIds.contains(stmtOp.id())) {
                continue;
            }
            for (DbSpendingCandidate db : availableDb) {
                if (matchedDbIds.contains(db.id())) {
                    continue;
                }
                if (!amountsEqual(stmtOp.amount(), db.amount())) {
                    continue;
                }
                if (!currenciesCompatible(stmtOp.currency(), db.currency())) {
                    continue;
                }
                if (stmtOp.operationDate() == null || db.effectiveDate() == null) {
                    continue;
                }
                long diffDays = Math.abs(ChronoUnit.DAYS.between(stmtOp.operationDate(), db.effectiveDate()));
                if (diffDays == 0 || diffDays > 3) {
                    continue;
                }
                if (!accountsCompatible(stmtOp.srcAccountShort(), db)) {
                    continue;
                }
                int score = scoreStoredPair(stmtOp, db, diffDays);
                pass2.add(new PotentialStoredMatch(stmtOp.id(), db.id(), "DATE_SHIFT", (int) diffDays, score));
            }
        }
        pass2.sort(Comparator.comparingInt(PotentialStoredMatch::score).reversed());

        for (PotentialStoredMatch m : pass2) {
            if (matchedStmtIds.contains(m.stmtId()) || matchedDbIds.contains(m.dbId())) {
                continue;
            }
            matchedStmtIds.add(m.stmtId());
            matchedDbIds.add(m.dbId());

            if (m.dateDiffDays() == 1 && m.score() >= 115) {
                jdbcTemplate.update(
                        """
                        UPDATE mbank_statement_operations
                        SET reconciliation_status = 'MATCHED_EMAIL',
                            match_type = 'DATE_SHIFT_1D',
                            date_diff_days = ?,
                            match_score = ?,
                            spending_id = ?,
                            candidate_spending_id = NULL,
                            reconciled_at = NOW()
                        WHERE id = ?
                        """,
                        m.dateDiffDays(),
                        m.score(),
                        m.dbId(),
                        m.stmtId()
                );
            } else {
                jdbcTemplate.update(
                        """
                        UPDATE mbank_statement_operations
                        SET reconciliation_status = 'CANDIDATE_DATE_SHIFT',
                            match_type = 'DATE_SHIFT',
                            date_diff_days = ?,
                            match_score = ?,
                            candidate_spending_id = ?
                        WHERE id = ?
                        """,
                        m.dateDiffDays(),
                        m.score(),
                        m.dbId(),
                        m.stmtId()
                );
            }
        }

        // Pass 3: same amount + compatible account + matching title, 4..LONG_SHIFT_MAX_DAYS days apart
        // -> CANDIDATE_DATE_SHIFT for user review. Typical case: card refunds ("ZWROT ZAKUPU"), which the statement
        // dates with the original purchase date while the e-mail carries the (much later) booking date.
        List<PotentialStoredMatch> pass3 = new ArrayList<>();
        for (StoredStatementOpRow stmtOp : pendingOps) {
            if (matchedStmtIds.contains(stmtOp.id())) {
                continue;
            }
            for (DbSpendingCandidate db : availableDb) {
                if (matchedDbIds.contains(db.id())
                        || !amountsEqual(stmtOp.amount(), db.amount())
                        || !currenciesCompatible(stmtOp.currency(), db.currency())
                        || stmtOp.operationDate() == null || db.effectiveDate() == null
                        || !accountsCompatible(stmtOp.srcAccountShort(), db)
                        || !titlesMatch(stmtOp.title(), stmtOp.rawDescription(), db.title())) {
                    continue;
                }
                long diffDays = Math.abs(ChronoUnit.DAYS.between(stmtOp.operationDate(), db.effectiveDate()));
                if (diffDays <= 3 || diffDays > LONG_SHIFT_MAX_DAYS) {
                    continue;
                }
                // closer dates first: score decreases by 1 per day on top of the usual pair score (without day penalty)
                int score = scoreStoredPair(stmtOp, db, 0) - (int) diffDays;
                pass3.add(new PotentialStoredMatch(stmtOp.id(), db.id(), "DATE_SHIFT_LONG", (int) diffDays, score));
            }
        }
        pass3.sort(Comparator.comparingInt(PotentialStoredMatch::score).reversed());

        for (PotentialStoredMatch m : pass3) {
            if (matchedStmtIds.contains(m.stmtId()) || matchedDbIds.contains(m.dbId())) {
                continue;
            }
            matchedStmtIds.add(m.stmtId());
            matchedDbIds.add(m.dbId());
            jdbcTemplate.update(
                    """
                    UPDATE mbank_statement_operations
                    SET reconciliation_status = 'CANDIDATE_DATE_SHIFT',
                        match_type = 'DATE_SHIFT_LONG',
                        date_diff_days = ?,
                        match_score = ?,
                        candidate_spending_id = ?
                    WHERE id = ?
                    """,
                    m.dateDiffDays(),
                    m.score(),
                    m.dbId(),
                    m.stmtId()
            );
        }
    }

    /** Maximum date difference (days) for title-confirmed candidates (pass 3), e.g. card refunds. */
    private static final int LONG_SHIFT_MAX_DAYS = 31;

    /**
     * True when the e-mail title clearly refers to the same counterparty as the statement row. E-mail titles are
     * often truncated by mBank ("Deporvillage...", "JMP S.A. BIE..."), so the part before "..." (and, for transfers,
     * the part before ";") must be at least 4 characters and be a prefix of the statement title or description.
     */
    private boolean titlesMatch(String stmtTitle, String stmtRawDesc, String dbTitle) {
        if (dbTitle == null || dbTitle.isBlank()) {
            return false;
        }
        String head = dbTitle;
        int dots = head.indexOf("...");
        if (dots >= 0) {
            head = head.substring(0, dots);
        }
        int semicolon = head.indexOf(';');
        if (semicolon >= 0) {
            head = head.substring(0, semicolon);
        }
        String normHead = normalizeForComparison(head);
        if (normHead.length() < 4) {
            return false;
        }
        String normTitle = normalizeForComparison(stmtTitle);
        String normRaw = normalizeForComparison(stmtRawDesc);
        return normTitle.startsWith(normHead) || normRaw.startsWith(normHead)
                || (!normTitle.isEmpty() && normHead.startsWith(normTitle) && normTitle.length() >= 4);
    }

    /**
     * Promotes the given mbank_statement_operations IDs into the spendings table,
     * runs EmailProcessingService.enrichSpendingDetails() so accounts, categories, PLN amounts,
     * duplicates, card auths, and Allegro links are populated, and applies mBank's category
     * as a fallback if dictionary_category has a matching category name.
     */
    @Transactional
    public int promoteStatementOperations(List<Long> statementOpIds) {
        return promoteStatementOperations(statementOpIds, Map.of());
    }

    /**
     * Same as {@link #promoteStatementOperations(List)}, but additionally applies user-chosen categories
     * (keyed by statement operation id). A user-chosen category always wins over automatic assignment.
     */
    @Transactional
    public int promoteStatementOperations(List<Long> statementOpIds, Map<Long, String> userCategories) {
        if (statementOpIds == null || statementOpIds.isEmpty()) {
            return 0;
        }

        int promotedCount = 0;
        List<Long> newlyCreatedSpendingIds = new ArrayList<>();
        Map<Long, Long> spendingIdByStatementOpId = new HashMap<>();

        for (Long stmtOpId : statementOpIds) {
            if (stmtOpId == null) {
                continue;
            }
            List<StoredStatementOpRow> rows = jdbcTemplate.query(
                    """
                    SELECT id, row_fingerprint, operation_date, same_day_sequence,
                           title, operation_type, raw_description,
                           account_name, src_account_short, src_account_full,
                           dst_account_full, dst_account_short, mbank_category,
                           amount, currency, reconciliation_status, spending_id
                    FROM mbank_statement_operations
                    WHERE id = ?
                    """,
                    (rs, rowNum) -> new StoredStatementOpRow(
                            rs.getLong("id"),
                            rs.getString("row_fingerprint"),
                            rs.getDate("operation_date") != null ? rs.getDate("operation_date").toLocalDate() : null,
                            rs.getInt("same_day_sequence"),
                            rs.getString("title"),
                            rs.getString("operation_type"),
                            rs.getString("raw_description"),
                            rs.getString("account_name"),
                            rs.getString("src_account_short"),
                            rs.getString("src_account_full"),
                            rs.getString("dst_account_full"),
                            rs.getString("dst_account_short"),
                            rs.getString("mbank_category"),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency"),
                            rs.getString("reconciliation_status"),
                            rs.getObject("spending_id") != null ? rs.getLong("spending_id") : null
                    ),
                    stmtOpId
            );

            if (rows.isEmpty()) {
                continue;
            }
            StoredStatementOpRow op = rows.getFirst();
            if (op.spendingId() != null) {
                continue;
            }

            String operationId = "stmt:" + op.rowFingerprint();
            String mappedOperationType = mapStatementOperationType(op.operationType(), op.amount());
            String operationDateStr = op.operationDate() != null ? op.operationDate().toString() : null;

            // Check if operation_id already exists in spendings
            List<Long> existingSpendingIds = jdbcTemplate.queryForList(
                    "SELECT id FROM spendings WHERE operation_id = ? OR statement_operation_id = ?",
                    Long.class,
                    operationId,
                    op.id()
            );

            Long spendingId;
            if (!existingSpendingIds.isEmpty()) {
                spendingId = existingSpendingIds.getFirst();
            } else {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            """
                            INSERT INTO spendings (
                                raw_email_id, statement_operation_id, operation_id,
                                operation_date, operation_time, operation_type,
                                src_account, dst_account, amount, currency,
                                name, details
                            ) VALUES (NULL, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            new String[]{"id"}
                    );
                    ps.setLong(1, op.id());
                    ps.setString(2, operationId);
                    ps.setString(3, operationDateStr);
                    ps.setString(4, mappedOperationType);
                    ps.setString(5, op.srcAccountShort());
                    ps.setString(6, op.dstAccountShort());
                    ps.setBigDecimal(7, op.amount());
                    ps.setString(8, op.currency() != null ? op.currency() : "PLN");
                    ps.setString(9, op.title());
                    ps.setString(10, op.rawDescription());
                    return ps;
                }, keyHolder);
                Number key = keyHolder.getKey();
                if (key == null) {
                    continue;
                }
                spendingId = key.longValue();
            }

            jdbcTemplate.update(
                    """
                    UPDATE mbank_statement_operations
                    SET spending_id = ?,
                        candidate_spending_id = NULL,
                        reconciliation_status = 'IMPORTED_TO_SPENDINGS',
                        match_type = 'PROMOTED',
                        reconciled_at = NOW()
                    WHERE id = ?
                    """,
                    spendingId,
                    op.id()
            );

            newlyCreatedSpendingIds.add(spendingId);
            spendingIdByStatementOpId.put(op.id(), spendingId);
            promotedCount++;
        }

        if (promotedCount > 0) {
            emailProcessingService.enrichSpendingDetails();

            // Fallback category assignment from mBank statement category if dictionary_category has an exact match
            for (Long spendingId : newlyCreatedSpendingIds) {
                jdbcTemplate.update(
                        """
                        UPDATE spending_details sd
                        SET category = dc.name,
                            updated_at = NOW()
                        FROM spendings s
                        JOIN mbank_statement_operations mso ON mso.id = s.statement_operation_id
                        JOIN dictionary_category dc ON LOWER(BTRIM(dc.name)) = LOWER(BTRIM(mso.mbank_category))
                        WHERE sd.spending_id = s.id
                          AND s.id = ?
                          AND (sd.category IS NULL OR BTRIM(sd.category) = '')
                        """,
                        spendingId
                );
            }
        }

        // User-chosen categories override anything assigned automatically
        if (userCategories != null) {
            for (Map.Entry<Long, String> entry : userCategories.entrySet()) {
                Long spendingId = spendingIdByStatementOpId.get(entry.getKey());
                String category = entry.getValue() != null ? entry.getValue().trim() : "";
                if (spendingId == null || category.isEmpty()) {
                    continue;
                }
                jdbcTemplate.update("INSERT INTO dictionary_category (name) VALUES (?) ON CONFLICT (name) DO NOTHING", category);
                jdbcTemplate.update(
                        """
                        INSERT INTO spending_details (spending_id, category, updated_at)
                        VALUES (?, ?, NOW())
                        ON CONFLICT (spending_id) DO UPDATE
                        SET category = EXCLUDED.category, updated_at = NOW()
                        """,
                        spendingId,
                        category
                );
            }
        }

        return promotedCount;
    }

    @Transactional
    public void linkStatementToSpending(Long statementOperationId, Long spendingId) {
        if (statementOperationId == null || spendingId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podaj operację z wyciągu i wydatek");
        }
        Integer spendingExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spendings WHERE id = ?", Integer.class, spendingId);
        if (spendingExists == null || spendingExists == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wydatek #" + spendingId + " nie istnieje");
        }
        List<Long> otherOps = jdbcTemplate.queryForList(
                "SELECT id FROM mbank_statement_operations WHERE spending_id = ? AND id <> ?",
                Long.class, spendingId, statementOperationId);
        if (!otherOps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Wydatek #" + spendingId + " jest już połączony z operacją z wyciągu #" + otherOps.getFirst());
        }
        int rows = jdbcTemplate.update(
                """
                UPDATE mbank_statement_operations
                SET spending_id = ?,
                    candidate_spending_id = NULL,
                    reconciliation_status = 'MATCHED_EMAIL',
                    match_type = 'MANUAL_LINK',
                    reconciled_at = NOW()
                WHERE id = ?
                """,
                spendingId,
                statementOperationId
        );
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operacja z wyciągu #" + statementOperationId + " nie istnieje");
        }
    }

    /**
     * Confirms several CANDIDATE_DATE_SHIFT suggestions at once: each statement operation is linked to its
     * candidate_spending_id. Operations that are no longer candidates, or whose candidate spending is already
     * linked to another operation, are skipped. Returns the number of linked operations.
     */
    @Transactional
    public int linkCandidates(List<Long> statementOperationIds) {
        if (statementOperationIds == null || statementOperationIds.isEmpty()) {
            return 0;
        }
        int linked = 0;
        for (Long id : statementOperationIds) {
            if (id == null) {
                continue;
            }
            linked += jdbcTemplate.update(
                    """
                    UPDATE mbank_statement_operations mso
                    SET spending_id = mso.candidate_spending_id,
                        candidate_spending_id = NULL,
                        reconciliation_status = 'MATCHED_EMAIL',
                        match_type = 'MANUAL_LINK',
                        reconciled_at = NOW()
                    WHERE mso.id = ?
                      AND mso.reconciliation_status = 'CANDIDATE_DATE_SHIFT'
                      AND mso.candidate_spending_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM mbank_statement_operations other
                          WHERE other.spending_id = mso.candidate_spending_id AND other.id <> mso.id
                      )
                    """,
                    id
            );
        }
        return linked;
    }

    /**
     * Removes a link between a statement operation and an e-mail spending, putting the operation back to UNRECONCILED.
     * Operations promoted to spendings (IMPORTED_TO_SPENDINGS) cannot be unlinked this way.
     */
    @Transactional
    public void unlinkStatementOperation(Long statementOperationId) {
        int rows = jdbcTemplate.update(
                """
                UPDATE mbank_statement_operations
                SET spending_id = NULL,
                    candidate_spending_id = NULL,
                    reconciliation_status = 'UNRECONCILED',
                    match_type = NULL,
                    date_diff_days = NULL,
                    match_score = NULL,
                    reconciled_at = NULL
                WHERE id = ?
                  AND reconciliation_status = 'MATCHED_EMAIL'
                """,
                statementOperationId
        );
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Operację #" + statementOperationId + " można rozłączyć tylko, gdy jest połączona z wydatkiem z e-maila");
        }
    }

    /**
     * Spendings that can be manually linked to the statement operation: within ±days of its date (or matching
     * the free-text query in a wider ±180 day window), not linked to any other statement operation and not created
     * from a statement. Sorted by amount difference, then date difference.
     */
    public List<DbSpendingCandidate> findLinkCandidates(Long statementOperationId, int days, String query) {
        List<Map<String, Object>> ops = jdbcTemplate.queryForList(
                "SELECT operation_date, amount FROM mbank_statement_operations WHERE id = ?", statementOperationId);
        if (ops.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Operacja z wyciągu #" + statementOperationId + " nie istnieje");
        }
        LocalDate opDate = ((Date) ops.getFirst().get("operation_date")).toLocalDate();
        BigDecimal opAmount = (BigDecimal) ops.getFirst().get("amount");
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int window = q.isEmpty() ? Math.max(0, Math.min(days, 60)) : 180;

        Set<Long> linkedIds = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT spending_id FROM mbank_statement_operations WHERE spending_id IS NOT NULL AND id <> ?",
                Long.class, statementOperationId));

        return fetchDbCandidates(opDate.minusDays(window), opDate.plusDays(window)).stream()
                .filter(c -> !linkedIds.contains(c.id()))
                .filter(c -> c.statementOperationId() == null)
                .filter(c -> q.isEmpty() || matchesQuery(c, q))
                .sorted(Comparator
                        .comparing((DbSpendingCandidate c) -> c.amount() == null || opAmount == null
                                ? BigDecimal.valueOf(Long.MAX_VALUE)
                                : c.amount().subtract(opAmount).abs())
                        .thenComparing(c -> c.effectiveDate() == null
                                ? Long.MAX_VALUE
                                : Math.abs(java.time.temporal.ChronoUnit.DAYS.between(opDate, c.effectiveDate()))))
                .limit(50)
                .toList();
    }

    private static boolean matchesQuery(DbSpendingCandidate c, String q) {
        String haystack = String.join(" ",
                Objects.toString(c.title(), ""),
                Objects.toString(c.operationType(), ""),
                Objects.toString(c.categoryName(), ""),
                Objects.toString(c.amount(), ""),
                String.valueOf(c.id())).toLowerCase(Locale.ROOT);
        return haystack.contains(q.replace(',', '.'));
    }

    @Transactional
    public int setIgnoredStatus(List<Long> statementOpIds, boolean ignored) {
        if (statementOpIds == null || statementOpIds.isEmpty()) {
            return 0;
        }
        int updated = 0;
        String targetStatus = ignored ? "IGNORED" : "UNRECONCILED";
        for (Long id : statementOpIds) {
            if (id == null) {
                continue;
            }
            updated += jdbcTemplate.update(
                    """
                    UPDATE mbank_statement_operations
                    SET reconciliation_status = ?,
                        reconciled_at = CASE WHEN ? THEN NOW() ELSE NULL END
                    WHERE id = ?
                      AND spending_id IS NULL
                    """,
                    targetStatus,
                    ignored,
                    id
            );
        }
        return updated;
    }

    private ReconciliationReport buildReconciliationReport(
            LocalDate fromDate,
            LocalDate toDate,
            int newlyInsertedCount,
            int alreadyExistedCount
    ) {
        LocalDate dbQueryFrom = fromDate.minusDays(4);
        LocalDate dbQueryTo = toDate.plusDays(4);
        List<DbSpendingCandidate> dbCandidates = fetchDbCandidates(dbQueryFrom, dbQueryTo);
        Map<Long, DbSpendingCandidate> dbById = new HashMap<>();
        for (DbSpendingCandidate c : dbCandidates) {
            dbById.put(c.id(), c);
        }

        List<StoredStatementOperationDto> allOps = jdbcTemplate.query(
                """
                SELECT id, row_fingerprint, operation_date, same_day_sequence,
                       title, operation_type, raw_description,
                       account_name, src_account_short, src_account_full,
                       dst_account_full, dst_account_short, mbank_category,
                       amount, currency, reconciliation_status, match_type,
                       date_diff_days, match_score, spending_id, candidate_spending_id
                FROM mbank_statement_operations
                WHERE operation_date >= ? AND operation_date <= ?
                ORDER BY operation_date DESC, id ASC
                """,
                (rs, rowNum) -> {
                    Long spendingId = rs.getObject("spending_id") != null ? rs.getLong("spending_id") : null;
                    Long candidateId = rs.getObject("candidate_spending_id") != null ? rs.getLong("candidate_spending_id") : null;
                    return new StoredStatementOperationDto(
                            rs.getLong("id"),
                            rs.getString("row_fingerprint"),
                            rs.getDate("operation_date") != null ? rs.getDate("operation_date").toLocalDate().toString() : null,
                            rs.getInt("same_day_sequence"),
                            rs.getString("title"),
                            rs.getString("operation_type"),
                            rs.getString("raw_description"),
                            rs.getString("account_name"),
                            rs.getString("src_account_short"),
                            rs.getString("src_account_full"),
                            rs.getString("dst_account_full"),
                            rs.getString("dst_account_short"),
                            rs.getString("mbank_category"),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency"),
                            rs.getString("reconciliation_status"),
                            rs.getString("match_type"),
                            rs.getObject("date_diff_days") != null ? rs.getInt("date_diff_days") : null,
                            rs.getObject("match_score") != null ? rs.getInt("match_score") : null,
                            spendingId,
                            candidateId,
                            spendingId != null ? dbById.get(spendingId) : null,
                            candidateId != null ? dbById.get(candidateId) : null
                    );
                },
                Date.valueOf(fromDate),
                Date.valueOf(toDate)
        );

        List<StoredStatementOperationDto> unReconciled = new ArrayList<>();
        List<StoredStatementOperationDto> candidateDateShift = new ArrayList<>();
        List<StoredStatementOperationDto> matchedEmail = new ArrayList<>();
        List<StoredStatementOperationDto> importedToSpendings = new ArrayList<>();
        List<StoredStatementOperationDto> ignored = new ArrayList<>();
        Set<Long> linkedSpendingIds = new HashSet<>();

        for (StoredStatementOperationDto dto : allOps) {
            if (dto.spendingId() != null) {
                linkedSpendingIds.add(dto.spendingId());
            }
            if (dto.candidateSpendingId() != null) {
                linkedSpendingIds.add(dto.candidateSpendingId());
            }
            String status = dto.reconciliationStatus() != null ? dto.reconciliationStatus() : "UNRECONCILED";
            switch (status) {
                case "CANDIDATE_DATE_SHIFT" -> candidateDateShift.add(dto);
                case "MATCHED_EMAIL" -> matchedEmail.add(dto);
                case "IMPORTED_TO_SPENDINGS" -> importedToSpendings.add(dto);
                case "IGNORED" -> ignored.add(dto);
                default -> unReconciled.add(dto);
            }
        }

        List<DbSpendingCandidate> onlyInDb = dbCandidates.stream()
                .filter(db -> db.effectiveDate() != null
                        && !db.effectiveDate().isBefore(fromDate)
                        && !db.effectiveDate().isAfter(toDate))
                .filter(db -> !linkedSpendingIds.contains(db.id()) && db.statementOperationId() == null)
                .toList();

        return new ReconciliationReport(
                fromDate.toString(),
                toDate.toString(),
                allOps.size(),
                newlyInsertedCount,
                alreadyExistedCount,
                unReconciled.size(),
                candidateDateShift.size(),
                matchedEmail.size(),
                importedToSpendings.size(),
                ignored.size(),
                onlyInDb.size(),
                sumStoredAmounts(unReconciled),
                sumStoredAmounts(candidateDateShift),
                sumStoredAmounts(matchedEmail),
                sumStoredAmounts(importedToSpendings),
                onlyInDb.stream().map(DbSpendingCandidate::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add),
                unReconciled,
                candidateDateShift,
                matchedEmail,
                importedToSpendings,
                ignored,
                onlyInDb
        );
    }

    private BigDecimal sumStoredAmounts(List<StoredStatementOperationDto> list) {
        return list.stream()
                .map(StoredStatementOperationDto::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record StoredStatementOpRow(
            Long id,
            String rowFingerprint,
            LocalDate operationDate,
            int sameDaySequence,
            String title,
            String operationType,
            String rawDescription,
            String accountName,
            String srcAccountShort,
            String srcAccountFull,
            String dstAccountFull,
            String dstAccountShort,
            String mbankCategory,
            BigDecimal amount,
            String currency,
            String reconciliationStatus,
            Long spendingId
    ) {}

    private record PotentialStoredMatch(
            Long stmtId,
            Long dbId,
            String matchType,
            int dateDiffDays,
            int score
    ) {}

    private List<StoredStatementOpRow> fetchPendingStatementOps(LocalDate fromDate, LocalDate toDate) {
        return jdbcTemplate.query(
                """
                SELECT id, row_fingerprint, operation_date, same_day_sequence,
                       title, operation_type, raw_description,
                       account_name, src_account_short, src_account_full,
                       dst_account_full, dst_account_short, mbank_category,
                       amount, currency, reconciliation_status, spending_id
                FROM mbank_statement_operations
                WHERE operation_date >= ? AND operation_date <= ?
                  AND reconciliation_status IN ('UNRECONCILED', 'CANDIDATE_DATE_SHIFT')
                  AND spending_id IS NULL
                ORDER BY operation_date DESC, id ASC
                """,
                (rs, rowNum) -> new StoredStatementOpRow(
                        rs.getLong("id"),
                        rs.getString("row_fingerprint"),
                        rs.getDate("operation_date") != null ? rs.getDate("operation_date").toLocalDate() : null,
                        rs.getInt("same_day_sequence"),
                        rs.getString("title"),
                        rs.getString("operation_type"),
                        rs.getString("raw_description"),
                        rs.getString("account_name"),
                        rs.getString("src_account_short"),
                        rs.getString("src_account_full"),
                        rs.getString("dst_account_full"),
                        rs.getString("dst_account_short"),
                        rs.getString("mbank_category"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("reconciliation_status"),
                        rs.getObject("spending_id") != null ? rs.getLong("spending_id") : null
                ),
                Date.valueOf(fromDate),
                Date.valueOf(toDate)
        );
    }

    private String mapStatementOperationType(String rawOperationType, BigDecimal amount) {
        String upper = rawOperationType != null ? rawOperationType.toUpperCase(Locale.ROOT) : "";
        boolean isPositive = amount != null && amount.compareTo(BigDecimal.ZERO) > 0;
        if (upper.contains("PRZELEW")) {
            return isPositive ? "Przelew przychodzacy" : "Przelew wychodzacy";
        }
        return isPositive ? "Uznanie" : "Obciazenie";
    }

    private String normalizeShortAccount(String fullAccount, String shortAccount) {
        if (shortAccount != null && !shortAccount.isBlank()) {
            return shortAccount.trim();
        }
        if (fullAccount != null) {
            String digits = fullAccount.replaceAll("\\D", "");
            if (digits.length() == 26) {
                return digits.substring(0, 4) + digits.substring(22, 26);
            }
        }
        return null;
    }

    private String normalizeDescriptionForFingerprint(String rawDesc) {
        if (rawDesc == null) {
            return "";
        }
        return rawDesc
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute SHA-256 fingerprint", e);
        }
    }

    /**
     * Legacy dry-run comparison kept for backward compatibility.
     */
    public StatementDiffReport compareHtml(String htmlContent) {
        ParsedStatement parsed = statementParser.parse(htmlContent);
        List<StatementOperation> stmtOps = parsed.operations();

        LocalDate fromDate = parsed.fromDate();
        LocalDate toDate = parsed.toDate();
        for (StatementOperation op : stmtOps) {
            LocalDate opDate = parseIsoDate(op.operationDate());
            if (opDate != null) {
                if (fromDate == null || opDate.isBefore(fromDate)) {
                    fromDate = opDate;
                }
                if (toDate == null || opDate.isAfter(toDate)) {
                    toDate = opDate;
                }
            }
        }

        if (fromDate == null) {
            fromDate = LocalDate.now().minusYears(1);
        }
        if (toDate == null) {
            toDate = LocalDate.now();
        }

        LocalDate dbQueryFrom = fromDate.minusDays(4);
        LocalDate dbQueryTo = toDate.plusDays(4);

        List<DbSpendingCandidate> dbCandidates = fetchDbCandidates(dbQueryFrom, dbQueryTo);

        return runMatching(parsed, dbCandidates);
    }

    public StatementDiffReport runMatching(ParsedStatement parsed, List<DbSpendingCandidate> dbCandidates) {
        List<StatementOperation> stmtOps = parsed.operations();

        LocalDate fromDate = parsed.fromDate();
        LocalDate toDate = parsed.toDate();
        for (StatementOperation op : stmtOps) {
            LocalDate opDate = parseIsoDate(op.operationDate());
            if (opDate != null) {
                if (fromDate == null || opDate.isBefore(fromDate)) {
                    fromDate = opDate;
                }
                if (toDate == null || opDate.isAfter(toDate)) {
                    toDate = opDate;
                }
            }
        }

        if (fromDate == null) {
            fromDate = LocalDate.now().minusYears(1);
        }
        if (toDate == null) {
            toDate = LocalDate.now();
        }

        Set<Integer> matchedStmtIndices = new HashSet<>();
        Set<Long> matchedDbIds = new HashSet<>();

        List<MatchedPairDto> matchedExact = new ArrayList<>();
        List<MatchedPairDto> matchedDateShift = new ArrayList<>();

        List<PotentialMatch> pass1Candidates = new ArrayList<>();
        for (int i = 0; i < stmtOps.size(); i++) {
            StatementOperation stmtOp = stmtOps.get(i);
            LocalDate opDate = parseIsoDate(stmtOp.operationDate());
            if (opDate == null) {
                continue;
            }
            for (DbSpendingCandidate db : dbCandidates) {
                if (!amountsEqual(stmtOp.amount(), db.amount())) {
                    continue;
                }
                if (!currenciesCompatible(stmtOp.currency(), db.currency())) {
                    continue;
                }
                if (!Objects.equals(opDate, db.effectiveDate())) {
                    continue;
                }
                if (!accountsCompatible(stmtOp.accountShort8(), db)) {
                    continue;
                }
                int score = scorePair(stmtOp, db, 0);
                pass1Candidates.add(new PotentialMatch(i, db.id(), stmtOp, db, 0, score));
            }
        }

        pass1Candidates.sort(Comparator.comparingInt(PotentialMatch::score).reversed());
        for (PotentialMatch m : pass1Candidates) {
            if (matchedStmtIndices.contains(m.stmtIndex()) || matchedDbIds.contains(m.dbId())) {
                continue;
            }
            matchedStmtIndices.add(m.stmtIndex());
            matchedDbIds.add(m.dbId());
            matchedExact.add(new MatchedPairDto(m.stmtOp(), m.db(), "EXACT", 0, m.score()));
        }

        List<PotentialMatch> pass2Candidates = new ArrayList<>();
        for (int i = 0; i < stmtOps.size(); i++) {
            if (matchedStmtIndices.contains(i)) {
                continue;
            }
            StatementOperation stmtOp = stmtOps.get(i);
            LocalDate opDate = parseIsoDate(stmtOp.operationDate());
            if (opDate == null) {
                continue;
            }
            for (DbSpendingCandidate db : dbCandidates) {
                if (matchedDbIds.contains(db.id())) {
                    continue;
                }
                if (!amountsEqual(stmtOp.amount(), db.amount())) {
                    continue;
                }
                if (!currenciesCompatible(stmtOp.currency(), db.currency())) {
                    continue;
                }
                if (db.effectiveDate() == null) {
                    continue;
                }
                long diffDays = Math.abs(ChronoUnit.DAYS.between(opDate, db.effectiveDate()));
                if (diffDays == 0 || diffDays > 3) {
                    continue;
                }
                if (!accountsCompatible(stmtOp.accountShort8(), db)) {
                    continue;
                }
                int score = scorePair(stmtOp, db, diffDays);
                pass2Candidates.add(new PotentialMatch(i, db.id(), stmtOp, db, diffDays, score));
            }
        }

        pass2Candidates.sort(Comparator.comparingInt(PotentialMatch::score).reversed());
        for (PotentialMatch m : pass2Candidates) {
            if (matchedStmtIndices.contains(m.stmtIndex()) || matchedDbIds.contains(m.dbId())) {
                continue;
            }
            matchedStmtIndices.add(m.stmtIndex());
            matchedDbIds.add(m.dbId());
            matchedDateShift.add(new MatchedPairDto(m.stmtOp(), m.db(), "DATE_SHIFT", m.dateDiffDays(), m.score()));
        }

        List<StatementOperation> missingInDb = new ArrayList<>();
        for (int i = 0; i < stmtOps.size(); i++) {
            if (!matchedStmtIndices.contains(i)) {
                missingInDb.add(stmtOps.get(i));
            }
        }

        LocalDate finalFromDate = fromDate;
        LocalDate finalToDate = toDate;
        List<DbSpendingCandidate> onlyInDb = dbCandidates.stream()
                .filter(db -> !matchedDbIds.contains(db.id()))
                .filter(db -> db.effectiveDate() != null
                        && !db.effectiveDate().isBefore(finalFromDate)
                        && !db.effectiveDate().isAfter(finalToDate))
                .toList();

        List<DbSpendingCandidate> dbInPeriod = dbCandidates.stream()
                .filter(db -> db.effectiveDate() != null
                        && !db.effectiveDate().isBefore(finalFromDate)
                        && !db.effectiveDate().isAfter(finalToDate))
                .toList();

        BigDecimal stmtTotal = stmtOps.stream()
                .map(StatementOperation::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal dbTotal = dbInPeriod.stream()
                .map(DbSpendingCandidate::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal missingInDbTotal = missingInDb.stream()
                .map(StatementOperation::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal onlyInDbTotal = onlyInDb.stream()
                .map(DbSpendingCandidate::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new StatementDiffReport(
                fromDate.toString(),
                toDate.toString(),
                stmtOps.size(),
                dbInPeriod.size(),
                matchedExact.size(),
                matchedDateShift.size(),
                missingInDb.size(),
                onlyInDb.size(),
                stmtTotal,
                dbTotal,
                missingInDbTotal,
                onlyInDbTotal,
                parsed.accounts(),
                missingInDb,
                matchedDateShift,
                matchedExact,
                onlyInDb
        );
    }

    private record PotentialMatch(
            int stmtIndex,
            Long dbId,
            StatementOperation stmtOp,
            DbSpendingCandidate db,
            long dateDiffDays,
            int score
    ) {}

    private boolean amountsEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    private boolean currenciesCompatible(String c1, String c2) {
        String norm1 = (c1 == null || c1.isBlank()) ? "PLN" : c1.trim().toUpperCase(Locale.ROOT);
        String norm2 = (c2 == null || c2.isBlank()) ? "PLN" : c2.trim().toUpperCase(Locale.ROOT);
        return norm1.equals(norm2);
    }

    /**
     * The statement row's own account must be one of the e-mail transaction's accounts. For outgoing operations
     * it is the e-mail's source account, for incoming transfers ("Przelew przych. z rach. 3212...392744 na rach.
     * 42109862") it is the destination account, so both sides are checked. Unknown accounts are compatible.
     */
    private boolean accountsCompatible(String stmtOwnShort, DbSpendingCandidate db) {
        if (stmtOwnShort == null || stmtOwnShort.isBlank()) {
            return true;
        }
        boolean dbHasSrc = db.srcAccount() != null && !db.srcAccount().isBlank();
        boolean dbHasDst = db.dstAccount() != null && !db.dstAccount().isBlank();
        if (!dbHasSrc && !dbHasDst) {
            return true;
        }
        return sameAccount(stmtOwnShort, db.srcAccount()) || sameAccount(stmtOwnShort, db.dstAccount());
    }

    /** Compares account numbers by digits, ignoring leading zeros ("00561357" == "561357"). */
    private static boolean sameAccount(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String da = a.replaceAll("\\D", "").replaceFirst("^0+", "");
        String db = b.replaceAll("\\D", "").replaceFirst("^0+", "");
        return !da.isEmpty() && da.equals(db);
    }

    private int scoreStoredPair(StoredStatementOpRow stmt, DbSpendingCandidate db, long dateDiffDays) {
        int score = 100 - (int) (dateDiffDays * 20);

        if (sameAccount(stmt.srcAccountShort(), db.srcAccount()) || sameAccount(stmt.srcAccountShort(), db.dstAccount())) {
            score += 30;
        }

        if (stmt.dstAccountShort() != null && db.dstAccount() != null
                && stmt.dstAccountShort().equals(db.dstAccount())) {
            score += 25;
        }

        score += titleSimilarityBonus(stmt.title(), stmt.rawDescription(), db.title());
        return score;
    }

    private int scorePair(StatementOperation stmt, DbSpendingCandidate db, long dateDiffDays) {
        int score = 100 - (int) (dateDiffDays * 20);

        if (sameAccount(stmt.accountShort8(), db.srcAccount()) || sameAccount(stmt.accountShort8(), db.dstAccount())) {
            score += 30;
        }

        String dstShort = normalizeShortAccount(stmt.dstAccount(), null);
        if (dstShort != null && db.dstAccount() != null
                && dstShort.equals(db.dstAccount())) {
            score += 25;
        }

        score += titleSimilarityBonus(stmt.title(), stmt.rawDescription(), db.title());
        return score;
    }

    private int titleSimilarityBonus(String stmtTitle, String stmtRawDesc, String dbTitle) {
        if (dbTitle == null || dbTitle.isBlank()) {
            return 0;
        }
        String normDb = normalizeForComparison(dbTitle);
        String normStmtTitle = normalizeForComparison(stmtTitle);
        String normStmtRaw = normalizeForComparison(stmtRawDesc);

        if (normDb.isEmpty()) {
            return 0;
        }
        if (normDb.equals(normStmtTitle)) {
            return 40;
        }
        if (!normStmtTitle.isEmpty() && (normDb.contains(normStmtTitle) || normStmtTitle.contains(normDb))) {
            return 30;
        }
        if (!normStmtRaw.isEmpty() && normStmtRaw.contains(normDb)) {
            return 25;
        }

        Set<String> dbTokens = tokenize(normDb);
        Set<String> stmtTokens = tokenize(normStmtRaw);
        if (dbTokens.isEmpty() || stmtTokens.isEmpty()) {
            return 0;
        }
        int common = 0;
        for (String token : dbTokens) {
            if (stmtTokens.contains(token)) {
                common++;
            }
        }
        if (common > 0) {
            return Math.min(25, common * 10);
        }
        return 0;
    }

    private String normalizeForComparison(String s) {
        if (s == null) {
            return "";
        }
        return s.toUpperCase(Locale.ROOT)
                .replace('Ą', 'A')
                .replace('Ć', 'C')
                .replace('Ę', 'E')
                .replace('Ł', 'L')
                .replace('Ń', 'N')
                .replace('Ó', 'O')
                .replace('Ś', 'S')
                .replace('Ź', 'Z')
                .replace('Ż', 'Z')
                .replaceAll("[^A-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> tokenize(String normalized) {
        Set<String> result = new HashSet<>();
        for (String part : normalized.split(" ")) {
            if (part.length() >= 3) {
                result.add(part);
            }
        }
        return result;
    }

    private List<DbSpendingCandidate> fetchDbCandidates(LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                """
                WITH normalized AS (
                    SELECT
                        s.id,
                        COALESCE(
                            CASE
                                WHEN s.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$' THEN s.operation_date::date
                                WHEN s.operation_date ~ '^\\d{2}\\.\\d{2}\\.\\d{4}$' THEN TO_DATE(s.operation_date, 'DD.MM.YYYY')
                                WHEN s.operation_date ~ '^\\d{2}-\\d{2}-\\d{4}$' THEN TO_DATE(s.operation_date, 'DD-MM-YYYY')
                            END,
                            (re.email_date AT TIME ZONE 'Europe/Warsaw')::date
                        ) AS effective_date,
                        s.operation_date AS raw_operation_date,
                        COALESCE(NULLIF(BTRIM(s.name), ''), s.details) AS title,
                        s.operation_type,
                        s.amount,
                        s.currency,
                        s.amount_pln,
                        s.src_account,
                        s.dst_account,
                        sd.category AS category_name,
                        da.key AS account_key,
                        s.raw_email_id,
                        s.statement_operation_id
                    FROM spendings s
                    LEFT JOIN mbank_raw_emails re ON s.raw_email_id = re.id
                    LEFT JOIN spending_details sd ON s.id = sd.spending_id
                    LEFT JOIN dictionary_account da ON sd.account_id = da.id
                    WHERE sd.duplicate_of IS NULL
                )
                SELECT
                    id,
                    effective_date,
                    raw_operation_date,
                    title,
                    operation_type,
                    amount,
                    currency,
                    amount_pln,
                    src_account,
                    dst_account,
                    category_name,
                    account_key,
                    raw_email_id,
                    statement_operation_id
                FROM normalized
                WHERE effective_date >= ? AND effective_date <= ?
                ORDER BY effective_date DESC, id DESC
                """,
                (rs, rowNum) -> {
                    Date effDate = rs.getDate("effective_date");
                    return new DbSpendingCandidate(
                            rs.getLong("id"),
                            effDate != null ? effDate.toLocalDate() : null,
                            rs.getString("raw_operation_date"),
                            rs.getString("title"),
                            rs.getString("operation_type"),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency"),
                            rs.getBigDecimal("amount_pln"),
                            rs.getString("src_account"),
                            rs.getString("dst_account"),
                            rs.getString("category_name"),
                            rs.getString("account_key"),
                            rs.getObject("raw_email_id") != null ? rs.getLong("raw_email_id") : null,
                            rs.getObject("statement_operation_id") != null ? rs.getLong("statement_operation_id") : null
                    );
                },
                Date.valueOf(from),
                Date.valueOf(to)
        );
    }
}
