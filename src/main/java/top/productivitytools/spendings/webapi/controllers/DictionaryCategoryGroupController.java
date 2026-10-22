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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD for {@code dictionary_category_group} and assignment of categories to groups
 * ({@code dictionary_category.group_id}). A category belongs to at most one group;
 * deleting a group ungroups its categories (FK ON DELETE SET NULL).
 */
@RestController
@RequestMapping("/api/dictionary/category-groups")
@RequiredArgsConstructor
public class DictionaryCategoryGroupController {

    private final JdbcTemplate jdbcTemplate;

    public record CategoryGroup(Long id, String name, int sortOrder, String color, List<String> categories) {}
    public record CategoryGroupRequest(String name, Integer sortOrder, String color) {}
    /** A dictionary category with its (optional) group. */
    public record CategoryAssignment(String name, Long groupId, String groupName) {}
    public record CategoryAssignmentRequest(Long groupId) {}

    @GetMapping
    public List<CategoryGroup> getAll() {
        Map<Long, CategoryGroup> byId = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT id, name, sort_order, color FROM dictionary_category_group ORDER BY sort_order, name",
                rs -> {
                    long id = rs.getLong("id");
                    byId.put(id, new CategoryGroup(id, rs.getString("name"), rs.getInt("sort_order"),
                            rs.getString("color"), new ArrayList<>()));
                });
        jdbcTemplate.query(
                "SELECT group_id, name FROM dictionary_category WHERE group_id IS NOT NULL ORDER BY name",
                rs -> {
                    CategoryGroup group = byId.get(rs.getLong("group_id"));
                    if (group != null) {
                        group.categories().add(rs.getString("name"));
                    }
                });
        return new ArrayList<>(byId.values());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryGroup create(@RequestBody CategoryGroupRequest request) {
        String name = requireText(request.name(), "name");
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        String color = blankToNull(request.color());
        try {
            Long id = jdbcTemplate.queryForObject(
                    "INSERT INTO dictionary_category_group (name, sort_order, color) VALUES (?, ?, ?) RETURNING id",
                    Long.class, name, sortOrder, color);
            return new CategoryGroup(id, name, sortOrder, color, new ArrayList<>());
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grupa '" + name + "' już istnieje");
        }
    }

    @PutMapping("/{id}")
    public CategoryGroup update(@PathVariable long id, @RequestBody CategoryGroupRequest request) {
        String name = requireText(request.name(), "name");
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        String color = blankToNull(request.color());
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE dictionary_category_group SET name = ?, sort_order = ?, color = ? WHERE id = ?",
                    name, sortOrder, color, id);
            if (rows == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak grupy o id " + id);
            }
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Grupa '" + name + "' już istnieje");
        }
        List<String> categories = jdbcTemplate.queryForList(
                "SELECT name FROM dictionary_category WHERE group_id = ? ORDER BY name", String.class, id);
        return new CategoryGroup(id, name, sortOrder, color, categories);
    }

    /** Deletes the group; its categories become ungrouped (FK ON DELETE SET NULL). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        int rows = jdbcTemplate.update("DELETE FROM dictionary_category_group WHERE id = ?", id);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak grupy o id " + id);
        }
        return ResponseEntity.noContent().build();
    }

    /** Every dictionary category with its current group (null = ungrouped). */
    @GetMapping("/categories")
    public List<CategoryAssignment> getCategories() {
        return jdbcTemplate.query(
                """
                SELECT c.name, c.group_id, g.name AS group_name
                FROM dictionary_category c
                LEFT JOIN dictionary_category_group g ON g.id = c.group_id
                ORDER BY c.name
                """,
                (rs, n) -> new CategoryAssignment(rs.getString("name"), rs.getObject("group_id", Long.class),
                        rs.getString("group_name")));
    }

    /** Assigns the category to a group ({@code groupId}) or clears the assignment ({@code groupId: null}). */
    @PutMapping("/categories/{name}/group")
    public CategoryAssignment assignGroup(@PathVariable String name, @RequestBody CategoryAssignmentRequest request) {
        Long groupId = request == null ? null : request.groupId();
        String groupName = null;
        if (groupId != null) {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT name FROM dictionary_category_group WHERE id = ?", String.class, groupId);
            if (names.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak grupy o id " + groupId);
            }
            groupName = names.get(0);
        }
        int rows = jdbcTemplate.update("UPDATE dictionary_category SET group_id = ? WHERE name = ?", groupId, name);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak kategorii '" + name + "'");
        }
        return new CategoryAssignment(name, groupId, groupName);
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
