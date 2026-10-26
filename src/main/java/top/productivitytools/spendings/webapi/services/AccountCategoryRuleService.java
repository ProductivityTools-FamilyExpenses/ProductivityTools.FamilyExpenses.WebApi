package top.productivitytools.spendings.webapi.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Applies account_category_rules: every spending whose source or destination account matches the rule's
 * account number (compared by the last 6 digits) gets the rule's category.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountCategoryRuleService {

    /** Number of trailing digits used to compare accounts (e-mails mask the rest of the counterparty account). */
    public static final int SUFFIX_LENGTH = 6;

    private final JdbcTemplate jdbcTemplate;

    public record Rule(Long id, String accountNumber, String label, String category, boolean overwrite) {}

    public record MatchedSpending(Long id, String operationDate, String operationType, String name,
                                  BigDecimal amount, String currency, String srcAccount, String dstAccount,
                                  String category, boolean willChange) {}

    /** SQL condition (spendings alias "s") matching the account suffix passed twice as parameters. */
    private static final String ACCOUNT_MATCH = """
            (
                (LENGTH(regexp_replace(COALESCE(s.src_account, ''), '\\D', '', 'g')) >= 6
                 AND RIGHT(regexp_replace(s.src_account, '\\D', '', 'g'), 6) = ?)
                OR
                (LENGTH(regexp_replace(COALESCE(s.dst_account, ''), '\\D', '', 'g')) >= 6
                 AND RIGHT(regexp_replace(s.dst_account, '\\D', '', 'g'), 6) = ?)
            )
            """;

    /** SQL condition (details alias "sd") deciding whether the rule changes the spending's category. */
    private static final String WILL_CHANGE = """
            (sd.category IS DISTINCT FROM ?
             AND (? OR sd.category IS NULL OR sd.category = ''))
            """;

    public static String normalizeAccountNumber(String accountNumber) {
        return accountNumber == null ? "" : accountNumber.replaceAll("\\D", "");
    }

    public static String suffix(String accountNumber) {
        String digits = normalizeAccountNumber(accountNumber);
        return digits.length() <= SUFFIX_LENGTH ? digits : digits.substring(digits.length() - SUFFIX_LENGTH);
    }

    public List<Rule> getRules() {
        return jdbcTemplate.query(
                "SELECT id, account_number, label, category, overwrite FROM account_category_rules ORDER BY id",
                (rs, n) -> new Rule(rs.getLong("id"), rs.getString("account_number"), rs.getString("label"),
                        rs.getString("category"), rs.getBoolean("overwrite")));
    }

    /** Number of spendings matched by the rule's account. */
    public int countMatched(Rule rule) {
        String sfx = suffix(rule.accountNumber());
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spendings s WHERE " + ACCOUNT_MATCH,
                Integer.class, sfx, sfx);
        return c == null ? 0 : c;
    }

    /** Number of matched spendings whose category would be changed by applying the rule. */
    public int countToChange(Rule rule) {
        String sfx = suffix(rule.accountNumber());
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spendings s JOIN spending_details sd ON sd.spending_id = s.id WHERE "
                        + ACCOUNT_MATCH + " AND " + WILL_CHANGE,
                Integer.class, sfx, sfx, rule.category(), rule.overwrite());
        return c == null ? 0 : c;
    }

    /** Spendings matched by the rule (newest first) with a flag telling whether applying would change them. */
    public List<MatchedSpending> getMatchedSpendings(Rule rule) {
        String sfx = suffix(rule.accountNumber());
        return jdbcTemplate.query(
                """
                SELECT s.id, s.operation_date, s.operation_type, s.name, s.amount, s.currency,
                       s.src_account, s.dst_account, sd.category,
                """ + WILL_CHANGE + """
                       AS will_change
                FROM spendings s
                LEFT JOIN spending_details sd ON sd.spending_id = s.id
                WHERE """ + ACCOUNT_MATCH + """
                ORDER BY s.operation_date DESC NULLS LAST, s.id DESC
                LIMIT 500
                """,
                (rs, n) -> new MatchedSpending(rs.getLong("id"), rs.getString("operation_date"),
                        rs.getString("operation_type"), rs.getString("name"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("src_account"), rs.getString("dst_account"),
                        rs.getString("category"), rs.getBoolean("will_change")),
                rule.category(), rule.overwrite(), sfx, sfx);
    }

    /** Applies all rules (in id order, so a later rule wins on conflict). Returns the number of updated rows. */
    public int applyAll() {
        int updated = 0;
        for (Rule rule : getRules()) {
            updated += apply(rule);
        }
        return updated;
    }

    public int apply(Rule rule) {
        String sfx = suffix(rule.accountNumber());
        if (sfx.length() < SUFFIX_LENGTH) {
            return 0;
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE spending_details sd
                SET category = ?,
                    updated_at = NOW()
                FROM spendings s
                WHERE sd.spending_id = s.id
                  AND """ + ACCOUNT_MATCH + " AND " + WILL_CHANGE,
                rule.category(), sfx, sfx, rule.category(), rule.overwrite());
        if (updated > 0) {
            log.info("Account rule {} ({}): assigned category '{}' to {} spendings",
                    rule.id(), rule.label(), rule.category(), updated);
        }
        return updated;
    }
}
