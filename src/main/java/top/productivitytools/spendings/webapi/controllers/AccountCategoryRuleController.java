package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import top.productivitytools.spendings.webapi.services.AccountCategoryRuleService;
import top.productivitytools.spendings.webapi.services.AccountCategoryRuleService.MatchedSpending;
import top.productivitytools.spendings.webapi.services.AccountCategoryRuleService.Rule;

import java.util.List;
import java.util.Map;

/**
 * CRUD for account_category_rules: account number → category for every spending sent from or to the account.
 * Rules are applied automatically while enriching spendings and on demand via POST /apply.
 */
@RestController
@RequestMapping("/api/dictionary/account-category-rules")
@RequiredArgsConstructor
public class AccountCategoryRuleController {

    private final JdbcTemplate jdbcTemplate;
    private final AccountCategoryRuleService ruleService;

    public record RuleDto(Long id, String accountNumber, String label, String category, boolean overwrite,
                          int matchedCount, int toChangeCount) {}

    public record RuleRequest(String accountNumber, String label, String category, Boolean overwrite) {}

    @GetMapping
    public List<RuleDto> getAll() {
        return ruleService.getRules().stream().map(this::toDto).toList();
    }

    @GetMapping("/{id}/spendings")
    public List<MatchedSpending> getMatchedSpendings(@PathVariable long id) {
        return ruleService.getMatchedSpendings(findRule(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDto create(@RequestBody RuleRequest request) {
        String account = requireAccount(request.accountNumber());
        String category = requireText(request.category(), "category");
        boolean overwrite = request.overwrite() == null || request.overwrite();
        try {
            Long id = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO account_category_rules (account_number, label, category, overwrite)
                    VALUES (?, ?, ?, ?) RETURNING id
                    """,
                    Long.class, account, blankToNull(request.label()), category, overwrite);
            return toDto(findRule(id));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rachunek " + account + " ma już regułę");
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kategoria '" + category + "' nie istnieje w słowniku");
        }
    }

    @PutMapping("/{id}")
    public RuleDto update(@PathVariable long id, @RequestBody RuleRequest request) {
        String account = requireAccount(request.accountNumber());
        String category = requireText(request.category(), "category");
        boolean overwrite = request.overwrite() == null || request.overwrite();
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE account_category_rules SET account_number = ?, label = ?, category = ?, overwrite = ? WHERE id = ?",
                    account, blankToNull(request.label()), category, overwrite, id);
            if (rows == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak reguły o id " + id);
            }
            return toDto(findRule(id));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rachunek " + account + " ma już regułę");
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kategoria '" + category + "' nie istnieje w słowniku");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        int rows = jdbcTemplate.update("DELETE FROM account_category_rules WHERE id = ?", id);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak reguły o id " + id);
        }
        return ResponseEntity.noContent().build();
    }

    /** Applies all rules to existing spendings now. */
    @PostMapping("/apply")
    public Map<String, Integer> applyAll() {
        return Map.of("updated", ruleService.applyAll());
    }

    private RuleDto toDto(Rule r) {
        return new RuleDto(r.id(), r.accountNumber(), r.label(), r.category(), r.overwrite(),
                ruleService.countMatched(r), ruleService.countToChange(r));
    }

    private Rule findRule(long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, account_number, label, category, overwrite FROM account_category_rules WHERE id = ?",
                    (rs, n) -> new Rule(rs.getLong("id"), rs.getString("account_number"), rs.getString("label"),
                            rs.getString("category"), rs.getBoolean("overwrite")),
                    id);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak reguły o id " + id);
        }
    }

    private static String requireAccount(String value) {
        String digits = AccountCategoryRuleService.normalizeAccountNumber(value);
        if (digits.length() < AccountCategoryRuleService.SUFFIX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Numer rachunku musi mieć co najmniej " + AccountCategoryRuleService.SUFFIX_LENGTH + " cyfr");
        }
        return digits;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pole '" + field + "' jest wymagane");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
