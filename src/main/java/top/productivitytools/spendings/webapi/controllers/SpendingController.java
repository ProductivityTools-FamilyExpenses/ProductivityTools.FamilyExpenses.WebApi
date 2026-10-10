package top.productivitytools.spendings.webapi.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.productivitytools.spendings.webapi.dto.SpendingResponse;
import top.productivitytools.spendings.webapi.services.EmailProcessingService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/spendings")
@RequiredArgsConstructor
public class SpendingController {

    private static final String SELECT_SPENDINGS_WITH_DETAILS = """
            SELECT s.id,
                   s.raw_email_id,
                   s.operation_id,
                   s.operation_date,
                   s.operation_time,
                   s.operation_type,
                   s.src_account,
                   s.dst_account,
                   s.amount,
                   s.currency,
                   s.name,
                   s.amount_left,
                   s.amount_left_currency,
                   s.details,
                   s.created_at,
                   sd.account,
                   sd.category,
                   sd.note,
                   sd.allegro_raw_email_id,
                   sd.updated_at AS details_updated_at
            FROM spendings s
            LEFT JOIN spending_details sd ON sd.spending_id = s.id
            ORDER BY s.id DESC
            """;

    private static final RowMapper<SpendingResponse> SPENDING_ROW_MAPPER = SpendingController::mapRow;

    private final JdbcTemplate jdbcTemplate;
    private final EmailProcessingService emailProcessingService;

    /** Result of a manual enrichment run: how many rows were filled and how many are still empty. */
    public record FillResult(int updated, int remainingEmpty) {}

    @GetMapping
    public List<SpendingResponse> getAllSpendings() {
        return jdbcTemplate.query(SELECT_SPENDINGS_WITH_DETAILS, SPENDING_ROW_MAPPER);
    }

    /** Fills missing {@code spending_details.account} from {@code dictionary_account}. */
    @PostMapping("/fill-accounts")
    public FillResult fillAccounts() {
        int updated = emailProcessingService.fillAccounts();
        return new FillResult(updated, countEmpty("account"));
    }

    /** Fills missing {@code spending_details.category} from account rules and {@code dictionary_category_mapping}. */
    @PostMapping("/fill-categories")
    public FillResult fillCategories() {
        int updated = emailProcessingService.fillCategories();
        return new FillResult(updated, countEmpty("category"));
    }

    private int countEmpty(String column) {
        // column is a fixed identifier chosen by this class, never user input
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM spending_details WHERE " + column + " IS NULL OR " + column + " = ''",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private static SpendingResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SpendingResponse(
                rs.getLong("id"),
                rs.getLong("raw_email_id"),
                rs.getString("operation_id"),
                rs.getString("operation_date"),
                rs.getString("operation_time"),
                rs.getString("operation_type"),
                rs.getString("src_account"),
                rs.getString("dst_account"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("name"),
                rs.getString("amount_left"),
                rs.getString("amount_left_currency"),
                rs.getString("details"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("account"),
                rs.getString("category"),
                rs.getString("note"),
                rs.getObject("allegro_raw_email_id", Long.class),
                rs.getObject("details_updated_at", OffsetDateTime.class)
        );
    }
}
