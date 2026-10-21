package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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

/**
 * CRUD for dictionary_category_mapping: transaction name (spendings.name, exact match) → category.
 * "Uzupełnij kategorie" on the transactions page applies the mappings to rows without a category.
 */
@RestController
@RequestMapping("/api/dictionary/category-mappings")
@RequiredArgsConstructor
public class DictionaryCategoryMappingController {

    private final JdbcTemplate jdbcTemplate;

    public record CategoryMapping(Long id, String key, String category, int spendingsCount) {}
    public record CategoryMappingRequest(String key, String category) {}
    /** Transaction name that has uncategorised spendings and no mapping yet. */
    public record UnmappedName(String name, int spendingsCount, String lastOperationDate) {}

    @GetMapping
    public List<CategoryMapping> getAll() {
        return jdbcTemplate.query(
                """
                SELECT m.id, m.key, m.category,
                       (SELECT COUNT(*) FROM spendings s WHERE s.name = m.key) AS cnt
                FROM dictionary_category_mapping m
                ORDER BY m.category, m.key
                """,
                (rs, n) -> new CategoryMapping(rs.getLong("id"), rs.getString("key"), rs.getString("category"), rs.getInt("cnt"))
        );
    }

    /** Names of spendings without a category that are not mapped yet – candidates to add, most frequent first. */
    @GetMapping("/unmapped")
    public List<UnmappedName> getUnmapped() {
        return jdbcTemplate.query(
                """
                SELECT s.name, COUNT(*) AS cnt, MAX(s.operation_date) AS last_date
                FROM spendings s
                JOIN spending_details sd ON sd.spending_id = s.id
                WHERE s.name IS NOT NULL AND s.name <> ''
                  AND (sd.category IS NULL OR sd.category = '')
                  AND sd.duplicate_of IS NULL
                  AND NOT EXISTS (SELECT 1 FROM dictionary_category_mapping m WHERE m.key = s.name)
                GROUP BY s.name
                ORDER BY cnt DESC, s.name
                LIMIT 200
                """,
                (rs, n) -> new UnmappedName(rs.getString("name"), rs.getInt("cnt"), rs.getString("last_date"))
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryMapping create(@RequestBody CategoryMappingRequest request) {
        String key = requireText(request.key(), "key");
        String category = requireText(request.category(), "category");
        try {
            Long id = jdbcTemplate.queryForObject(
                    "INSERT INTO dictionary_category_mapping (key, category) VALUES (?, ?) RETURNING id",
                    Long.class, key, category);
            return new CategoryMapping(id, key, category, countSpendings(key));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nazwa '" + key + "' ma już przypisaną kategorię");
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kategoria '" + category + "' nie istnieje w słowniku");
        }
    }

    @PutMapping("/{id}")
    public CategoryMapping update(@PathVariable long id, @RequestBody CategoryMappingRequest request) {
        String key = requireText(request.key(), "key");
        String category = requireText(request.category(), "category");
        try {
            int rows = jdbcTemplate.update("UPDATE dictionary_category_mapping SET key = ?, category = ? WHERE id = ?", key, category, id);
            if (rows == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak wpisu o id " + id);
            }
            return new CategoryMapping(id, key, category, countSpendings(key));
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nazwa '" + key + "' ma już przypisaną kategorię");
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kategoria '" + category + "' nie istnieje w słowniku");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        int rows = jdbcTemplate.update("DELETE FROM dictionary_category_mapping WHERE id = ?", id);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak wpisu o id " + id);
        }
        return ResponseEntity.noContent().build();
    }

    private int countSpendings(String key) {
        Integer c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM spendings WHERE name = ?", Integer.class, key);
        return c == null ? 0 : c;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pole '" + field + "' jest wymagane");
        }
        return value.trim();
    }
}
