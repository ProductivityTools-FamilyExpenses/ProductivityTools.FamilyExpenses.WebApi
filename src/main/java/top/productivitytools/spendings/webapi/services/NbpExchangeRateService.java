package top.productivitytools.spendings.webapi.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Fetches NBP table A mid rates and caches them in {@code exchange_rates}.
 * API: https://api.nbp.pl/api/exchangerates/rates/a/{code}/{from}/{to}/ (max 93 days per call, 404 when no data).
 */
@Service
@Slf4j
public class NbpExchangeRateService {

    private static final String NBP_BASE_URL = "https://api.nbp.pl/api";
    private static final int MAX_DAYS_PER_REQUEST = 93;

    private final JdbcTemplate jdbcTemplate;
    private final RestClient restClient;

    public NbpExchangeRateService(JdbcTemplate jdbcTemplate, RestClient.Builder restClientBuilder) {
        this.jdbcTemplate = jdbcTemplate;
        this.restClient = restClientBuilder.baseUrl(NBP_BASE_URL).build();
    }

    record NbpRate(String no, LocalDate effectiveDate, BigDecimal mid) {}
    record NbpRatesResponse(String table, String currency, String code, List<NbpRate> rates) {}

    /**
     * Makes sure rates for {@code currency} are cached for the whole range [from, to] (inclusive).
     * Days without a rate (weekends, holidays) simply have no row. Network errors are logged and swallowed –
     * conversion is retried on the next enrichment run.
     *
     * @return true when the cache is believed to be complete for the range
     */
    public boolean ensureRates(String currency, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return true;
        }
        LocalDate cursor = from;
        boolean ok = true;
        while (!cursor.isAfter(to)) {
            LocalDate chunkEnd = cursor.plusDays(MAX_DAYS_PER_REQUEST - 1);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            if (!isRangeCached(currency, cursor, chunkEnd)) {
                ok &= fetchAndStore(currency, cursor, chunkEnd);
            }
            cursor = chunkEnd.plusDays(1);
        }
        return ok;
    }

    /**
     * A chunk is considered cached if we already have at least one rate per ~7 days in it
     * (cheap heuristic that avoids re-fetching ranges that are complete but contain weekends/holidays).
     */
    private boolean isRangeCached(String currency, LocalDate from, LocalDate to) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exchange_rates WHERE currency = ? AND rate_date BETWEEN ? AND ?",
                Integer.class, currency, from, to);
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        // ~5/7 business days minus public holidays (observed: 59-60 rates per 93-day quarter)
        long expectedBusinessDaysMin = Math.max(1, days * 5 / 7 - 10);
        return count != null && count >= expectedBusinessDaysMin;
    }

    private boolean fetchAndStore(String currency, LocalDate from, LocalDate to) {
        try {
            NbpRatesResponse response = restClient.get()
                    .uri("/exchangerates/rates/a/{code}/{from}/{to}/?format=json", currency, from, to)
                    .retrieve()
                    .body(NbpRatesResponse.class);
            if (response == null || response.rates() == null) {
                return true;
            }
            for (NbpRate r : response.rates()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO exchange_rates (currency, rate_date, rate, nbp_table_no)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (currency, rate_date) DO UPDATE SET rate = EXCLUDED.rate, nbp_table_no = EXCLUDED.nbp_table_no
                        """,
                        currency, r.effectiveDate(), r.mid(), r.no());
            }
            log.info("NBP: cached {} {} rates for {}..{}", response.rates().size(), currency, from, to);
            return true;
        } catch (HttpClientErrorException.NotFound ex) {
            // no data in the range (e.g. only weekend days or a future date) – nothing to cache
            return true;
        } catch (Exception ex) {
            log.warn("NBP: failed to fetch {} rates for {}..{}: {}", currency, from, to, ex.getMessage());
            return false;
        }
    }
}
