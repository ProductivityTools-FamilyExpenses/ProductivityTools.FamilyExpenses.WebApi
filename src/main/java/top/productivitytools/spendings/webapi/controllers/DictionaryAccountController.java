package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
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

import java.util.List;
import java.util.Map;

/** CRUD for {@code dictionary_account}: maps account / card numbers to friendly names used in spending_details.account. */
@RestController
@RequestMapping("/api/dictionary/accounts")
@RequiredArgsConstructor
public class DictionaryAccountController {

    private final JdbcTemplate jdbcTemplate;

    public record DictionaryAccount(Long id, String key, String name) {}
    public record DictionaryAccountRequest(String key, String name) {}
    /** Account number seen in spendings but absent from the dictionary. */
    public record UnmappedAccount(String account, int spendingsCount, String lastOperationDate) {}

    @GetMapping
    public List<DictionaryAccount> getAll() {
        return jdbcTemplate.query(
                "SELECT id, key, name FROM dictionary_account ORDER BY name, key",
                (rs, n) -> new DictionaryAccount(rs.getLong("id"), rs.getString("key"), rs.getString("name"))
        );
    }

    /** Accounts referenced by spendings (src or dst) that have no dictionary entry – candidates to add. */
    @GetMapping("/unmapped")
    public List<UnmappedAccount> getUnmapped() {
        return jdbcTemplate.query(
                """
                WITH used AS (
                    SELECT src_account AS account, operation_date FROM spendings WHERE src_account IS NOT NULL AND src_account <> ''
                    UNION ALL
                    SELECT dst_account, operation_date FROM spendings WHERE dst_account IS NOT NULL AND dst_account <> ''
                )
                SELECT u.account, COUNT(*) AS cnt, MAX(u.operation_date) AS last_date
                FROM used u
                WHERE NOT EXISTS (
                    SELECT 1 FROM dictionary_account da
                    WHERE da.key = u.account OR LTRIM(da.key, '0') = LTRIM(u.account, '0')
                )
                GROUP BY u.account
                ORDER BY cnt DESC, u.account
                """,
                (rs, n) -> new UnmappedAccount(rs.getString("account"), rs.getInt("cnt"), rs.getString("last_date"))
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryAccount create(@RequestBody DictionaryAccountRequest request) {
        String key = requireText(request.key(), "key");
        String name = requireText(request.name(), "name");
        try {
            Long id = jdbcTemplate.queryForObject(
                    "INSERT INTO dictionary_account (key, name) VALUES (?, ?) RETURNING id",
                    Long.class, key, name);
            return new DictionaryAccount(id, key, name);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Klucz '" + key + "' już istnieje w słowniku");
        }
    }

    @PutMapping("/{id}")
    public DictionaryAccount update(@PathVariable long id, @RequestBody DictionaryAccountRequest request) {
        String key = requireText(request.key(), "key");
        String name = requireText(request.name(), "name");
        try {
            int rows = jdbcTemplate.update("UPDATE dictionary_account SET key = ?, name = ? WHERE id = ?", key, name, id);
            if (rows == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak wpisu o id " + id);
            }
            return new DictionaryAccount(id, key, name);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Klucz '" + key + "' już istnieje w słowniku");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        int rows = jdbcTemplate.update("DELETE FROM dictionary_account WHERE id = ?", id);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak wpisu o id " + id);
        }
        return ResponseEntity.noContent().build();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pole '" + field + "' jest wymagane");
        }
        return value.trim();
    }
}
