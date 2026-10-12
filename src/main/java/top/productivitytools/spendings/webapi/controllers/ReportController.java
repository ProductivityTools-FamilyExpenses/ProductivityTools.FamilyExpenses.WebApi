package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Aggregated reports over spendings. Common rules:
 * <ul>
 *   <li>duplicates ({@code spending_details.duplicate_of IS NOT NULL}) are excluded,</li>
 *   <li>internal money movements are excluded: category {@code PrzelewWlasny} and FX conversion legs ({@code *.NAT.TR.FX}),</li>
 *   <li>amounts are taken from {@code amount_pln}; rows without a PLN amount yet are skipped (see hygiene report),</li>
 *   <li>category reports are <em>net</em>: a refund booked in the same category reduces that category's spending.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final JdbcTemplate jdbcTemplate;

    /** operation_date is text; only ISO dates are considered (all rows produced by the current parser). */
    private static final String OP_DATE = "CASE WHEN s.operation_date ~ '^\\d{4}-\\d{2}-\\d{2}$' THEN s.operation_date::date END";

    private static final String BASE_FILTER = """
              AND sd.duplicate_of IS NULL
              AND s.amount_pln IS NOT NULL
              AND COALESCE(sd.category, '') <> 'PrzelewWlasny'
              AND COALESCE(s.name, '') NOT LIKE '%.NAT.TR.FX'
            """;

    public record CategoryMonth(String month, String category, BigDecimal total, int count) {}
    public record CashflowMonth(String month, String account, BigDecimal income, BigDecimal expenses, BigDecimal net, int count) {}
    public record RecurringPayment(String payee, String dstAccount, String category, int occurrences, int months,
                                   LocalDate firstDate, LocalDate lastDate, BigDecimal lastAmount,
                                   BigDecimal avgAmount, BigDecimal minAmount, BigDecimal maxAmount,
                                   BigDecimal monthlyAvg12m, boolean active) {}
    public record HygieneCategory(String category, int count, BigDecimal total) {}
    public record Hygiene(int uncategorized, int withoutAccount, int withoutPln, int unmappedAccounts,
                          int unlinkedAuthorizations, int allegroEmailsWithoutSpending, int emailsInError,
                          int duplicates, List<HygieneCategory> proxyCategories, List<Map<String, Object>> uncategorizedSample,
                          String firstMonth) {}

    /** Report 1: net amount per category per month – expenses plus refunds/incomes booked in the same category. */
    @GetMapping("/category-monthly")
    public List<CategoryMonth> categoryMonthly(@RequestParam String from, @RequestParam String to) {
        LocalDate fromDate = YearMonth.parse(from).atDay(1);
        LocalDate toDate = YearMonth.parse(to).atEndOfMonth();
        return jdbcTemplate.query(
                """
                SELECT TO_CHAR(d.op_date, 'YYYY-MM') AS month,
                       COALESCE(NULLIF(d.category, ''), '(bez kategorii)') AS category,
                       SUM(d.amount_pln) AS total,
                       COUNT(*) AS cnt
                FROM (
                    SELECT {OP_DATE} AS op_date, sd.category, s.amount_pln
                    FROM spendings s
                    JOIN spending_details sd ON sd.spending_id = s.id
                    WHERE 1 = 1
                {BASE_FILTER}
                ) d
                WHERE d.op_date BETWEEN ? AND ?
                GROUP BY 1, 2
                ORDER BY 1, 2
                """.replace("{OP_DATE}", OP_DATE).replace("{BASE_FILTER}", BASE_FILTER),
                (rs, n) -> new CategoryMonth(rs.getString("month"), rs.getString("category"),
                        rs.getBigDecimal("total"), rs.getInt("cnt")),
                fromDate, toDate);
    }

    public record CategoryDetail(Long id, String operationDate, String operationTime, String operationType,
                                 String name, BigDecimal amount, String currency, BigDecimal amountPln,
                                 String account, String category, String note) {}

    /**
     * Drill-down for the pivot: the spendings behind a (category, month) cell. Same filters as the pivot.
     * {@code category} may be omitted (= all categories in the range) or be "(bez kategorii)" for uncategorised rows.
     */
    @GetMapping("/category-monthly/details")
    public List<CategoryDetail> categoryMonthlyDetails(@RequestParam String from, @RequestParam String to,
                                                       @RequestParam(required = false) String category) {
        LocalDate fromDate = YearMonth.parse(from).atDay(1);
        LocalDate toDate = YearMonth.parse(to).atEndOfMonth();
        boolean all = category == null || category.isBlank();
        boolean uncategorised = "(bez kategorii)".equals(category);
        return jdbcTemplate.query(
                """
                SELECT d.*
                FROM (
                    SELECT s.id, s.operation_date, s.operation_time, s.operation_type, s.name,
                           s.amount, s.currency, s.amount_pln, da.name AS account, sd.category, sd.note,
                           {OP_DATE} AS op_date
                    FROM spendings s
                    JOIN spending_details sd ON sd.spending_id = s.id
                    LEFT JOIN dictionary_account da ON da.id = sd.account_id
                    WHERE 1 = 1
                {BASE_FILTER}
                ) d
                WHERE d.op_date BETWEEN ? AND ?
                  AND (? OR (? AND (d.category IS NULL OR d.category = '')) OR d.category = ?)
                ORDER BY d.op_date, d.operation_time, d.id
                """.replace("{OP_DATE}", OP_DATE).replace("{BASE_FILTER}", BASE_FILTER),
                (rs, n) -> new CategoryDetail(rs.getLong("id"), rs.getString("operation_date"), rs.getString("operation_time"),
                        rs.getString("operation_type"), rs.getString("name"), rs.getBigDecimal("amount"), rs.getString("currency"),
                        rs.getBigDecimal("amount_pln"), rs.getString("account"), rs.getString("category"), rs.getString("note")),
                fromDate, toDate, all, uncategorised, category == null ? "" : category);
    }

    /** Report 2: income / expenses / net per month and account (account = dictionary name or raw number). */
    @GetMapping("/cashflow")
    public List<CashflowMonth> cashflow(@RequestParam String from, @RequestParam String to) {
        LocalDate fromDate = YearMonth.parse(from).atDay(1);
        LocalDate toDate = YearMonth.parse(to).atEndOfMonth();
        return jdbcTemplate.query(
                """
                SELECT TO_CHAR(d.op_date, 'YYYY-MM') AS month,
                       d.account,
                       COALESCE(SUM(CASE WHEN d.amount_pln > 0 THEN d.amount_pln END), 0) AS income,
                       COALESCE(SUM(CASE WHEN d.amount_pln < 0 THEN d.amount_pln END), 0) AS expenses,
                       SUM(d.amount_pln) AS net,
                       COUNT(*) AS cnt
                FROM (
                    SELECT {OP_DATE} AS op_date,
                           COALESCE(da.name,
                                    NULLIF(CASE WHEN s.amount_pln < 0 THEN s.src_account ELSE s.dst_account END, ''),
                                    NULLIF(s.src_account, ''), NULLIF(s.dst_account, ''), '?') AS account,
                           s.amount_pln
                    FROM spendings s
                    JOIN spending_details sd ON sd.spending_id = s.id
                    LEFT JOIN dictionary_account da ON da.id = sd.account_id
                    WHERE 1 = 1
                {BASE_FILTER}
                ) d
                WHERE d.op_date BETWEEN ? AND ?
                GROUP BY 1, 2
                ORDER BY 1, 2
                """.replace("{OP_DATE}", OP_DATE).replace("{BASE_FILTER}", BASE_FILTER),
                (rs, n) -> new CashflowMonth(rs.getString("month"), rs.getString("account"),
                        rs.getBigDecimal("income"), rs.getBigDecimal("expenses"), rs.getBigDecimal("net"), rs.getInt("cnt")),
                fromDate, toDate);
    }

    /**
     * Report 8: recurring outgoing transfers – same payee (first 14 significant chars of the recipient) and
     * target account, seen in at least {@code minMonths} distinct months. "active" = paid within the last 45 days.
     */
    @GetMapping("/recurring")
    public List<RecurringPayment> recurring(@RequestParam(defaultValue = "3") int minMonths) {
        return jdbcTemplate.query(
                """
                WITH t AS (
                    SELECT {OP_DATE} AS op_date,
                           s.dst_account,
                           TRIM(SPLIT_PART(s.name, ';', 1)) AS payee,
                           LEFT(REGEXP_REPLACE(UPPER(SPLIT_PART(s.name, ';', 1)), '[^A-Z0-9]', '', 'g'), 14) AS payee_key,
                           s.amount_pln,
                           sd.category
                    FROM spendings s
                    JOIN spending_details sd ON sd.spending_id = s.id
                    WHERE s.operation_type = 'Przelew wychodzacy'
                {BASE_FILTER}
                ),
                g AS (
                    SELECT payee_key, dst_account,
                           MIN(payee) AS payee,
                           MAX(category) AS category,
                           COUNT(*) AS occurrences,
                           COUNT(DISTINCT TO_CHAR(op_date, 'YYYY-MM')) AS months,
                           MIN(op_date) AS first_date,
                           MAX(op_date) AS last_date,
                           AVG(amount_pln) AS avg_amount,
                           MIN(amount_pln) AS min_amount,
                           MAX(amount_pln) AS max_amount,
                           SUM(CASE WHEN op_date >= CURRENT_DATE - INTERVAL '12 months' THEN amount_pln ELSE 0 END) / 12 AS monthly_avg_12m
                    FROM t
                    WHERE op_date IS NOT NULL
                    GROUP BY payee_key, dst_account
                    HAVING COUNT(DISTINCT TO_CHAR(op_date, 'YYYY-MM')) >= ?
                )
                SELECT g.*,
                       (SELECT t2.amount_pln FROM t t2
                         WHERE t2.payee_key = g.payee_key AND t2.dst_account = g.dst_account
                         ORDER BY t2.op_date DESC LIMIT 1) AS last_amount
                FROM g
                ORDER BY monthly_avg_12m ASC, last_date DESC
                """.replace("{OP_DATE}", OP_DATE).replace("{BASE_FILTER}", BASE_FILTER),
                (rs, n) -> new RecurringPayment(
                        rs.getString("payee"), rs.getString("dst_account"), rs.getString("category"),
                        rs.getInt("occurrences"), rs.getInt("months"),
                        rs.getObject("first_date", LocalDate.class), rs.getObject("last_date", LocalDate.class),
                        rs.getBigDecimal("last_amount"), rs.getBigDecimal("avg_amount"),
                        rs.getBigDecimal("min_amount"), rs.getBigDecimal("max_amount"),
                        rs.getBigDecimal("monthly_avg_12m"),
                        rs.getObject("last_date", LocalDate.class) != null
                                && !rs.getObject("last_date", LocalDate.class).isBefore(LocalDate.now().minusDays(45))),
                minMonths);
    }

    /** Report 6: data-quality counters. */
    @GetMapping("/hygiene")
    public Hygiene hygiene() {
        int uncategorized = count("""
                SELECT COUNT(*) FROM spendings s JOIN spending_details sd ON sd.spending_id = s.id
                WHERE sd.duplicate_of IS NULL AND (sd.category IS NULL OR sd.category = '')
                """);
        int withoutAccount = count("""
                SELECT COUNT(*) FROM spendings s JOIN spending_details sd ON sd.spending_id = s.id
                WHERE sd.duplicate_of IS NULL AND sd.account_id IS NULL
                """);
        int withoutPln = count("SELECT COUNT(*) FROM spendings WHERE amount_pln IS NULL");
        int unmappedAccounts = count("""
                WITH used AS (
                    SELECT src_account AS account FROM spendings WHERE src_account IS NOT NULL AND src_account <> ''
                    UNION SELECT dst_account FROM spendings WHERE dst_account IS NOT NULL AND dst_account <> ''
                )
                SELECT COUNT(*) FROM used u
                WHERE NOT EXISTS (SELECT 1 FROM dictionary_account da WHERE da.key = u.account OR LTRIM(da.key, '0') = LTRIM(u.account, '0'))
                """);
        int unlinkedAuth = count("SELECT COUNT(*) FROM card_authorizations WHERE settled_by_spending_id IS NULL");
        int allegroUnlinked = count("""
                SELECT COUNT(*) FROM allegro_raw_emails e
                WHERE e.status = 'PROCESSED'
                  AND NOT EXISTS (SELECT 1 FROM spending_details sd WHERE sd.allegro_raw_email_id = e.id)
                """);
        int emailsInError = count("""
                SELECT (SELECT COUNT(*) FROM mbank_raw_emails WHERE status = 'ERROR')
                     + (SELECT COUNT(*) FROM allegro_raw_emails WHERE status = 'ERROR')
                """);
        int duplicates = count("SELECT COUNT(*) FROM spending_details WHERE duplicate_of IS NOT NULL");
        List<HygieneCategory> proxy = jdbcTemplate.query(
                """
                SELECT sd.category, COUNT(*) AS cnt, COALESCE(SUM(s.amount_pln), 0) AS total
                FROM spendings s JOIN spending_details sd ON sd.spending_id = s.id
                WHERE sd.duplicate_of IS NULL AND sd.category LIKE 'Proxy%'
                GROUP BY sd.category ORDER BY cnt DESC
                """,
                (rs, n) -> new HygieneCategory(rs.getString("category"), rs.getInt("cnt"), rs.getBigDecimal("total")));
        List<Map<String, Object>> sample = jdbcTemplate.queryForList(
                """
                SELECT s.id, s.operation_date AS "operationDate", s.operation_type AS "operationType",
                       s.name, s.amount_pln AS "amountPln"
                FROM spendings s JOIN spending_details sd ON sd.spending_id = s.id
                WHERE sd.duplicate_of IS NULL AND (sd.category IS NULL OR sd.category = '')
                ORDER BY s.operation_date DESC, s.id DESC
                LIMIT 50
                """);
        String firstMonth = jdbcTemplate.queryForObject(
                "SELECT TO_CHAR(MIN(" + OP_DATE + "), 'YYYY-MM') FROM spendings s", String.class);
        return new Hygiene(uncategorized, withoutAccount, withoutPln, unmappedAccounts, unlinkedAuth,
                allegroUnlinked, emailsInError, duplicates, proxy, sample, firstMonth);
    }

    private int count(String sql) {
        Integer v = jdbcTemplate.queryForObject(sql, Integer.class);
        return v == null ? 0 : v;
    }
}
