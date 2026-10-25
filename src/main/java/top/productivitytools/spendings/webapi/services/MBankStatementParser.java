package top.productivitytools.spendings.webapi.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MBankStatementParser {

    private static final Pattern PERIOD_PATTERN = Pattern.compile(
            "za\\s+okres\\s+od\\s+(\\d{4}-\\d{2}-\\d{2})\\s+do\\s+(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HEADER_ACCOUNT_PATTERN = Pattern.compile(
            "^(.+?)\\s+-\\s+(\\d{26})$"
    );

    private static final Pattern MASK_DIGITS_PATTERN = Pattern.compile(
            "(\\d{4})\\s*\\.\\.\\.\\s*(\\d{4})"
    );

    private static final Pattern IBAN_26_PATTERN = Pattern.compile("^\\d{26}$");

    private static final Pattern AMOUNT_CURRENCY_PATTERN = Pattern.compile(
            "^([+-]?[\\d\\s\\u00A0]+(?:[.,]\\d{1,2})?)\\s*([A-Za-z]{3})?$"
    );

    private static final List<String> KNOWN_OPERATION_TYPES = List.of(
            "ZAKUP PRZY UŻYCIU KARTY - INTERNET",
            "ZAKUP PRZY UŻYCIU KARTY W KRAJU",
            "ZAKUP PRZY UŻYCIU KARTY ZA GRANICĄ",
            "BLIK ZAKUP E-COMMERCE",
            "BLIK ZAKUP",
            "BLIK P2P-WYCHODZĄCY",
            "BLIK P2P-PRZYCHODZĄCY",
            "BLIK WYPŁATA ATM WŁASNY",
            "BLIK WYPŁATA ATM KRAJOWY",
            "PRZELEW ZEWNĘTRZNY WYCHODZĄCY",
            "PRZELEW ZEWNĘTRZNY PRZYCHODZĄCY",
            "PRZELEW WEWNĘTRZNY WYCHODZĄCY",
            "PRZELEW WEWNĘTRZNY PRZYCHODZĄCY",
            "PRZELEW EXPRESS ELIXIR WYCH.",
            "PRZELEW EXPRESS ELIXIR PRZYCH.",
            "PRZELEW MTRANSFER WYCHODZACY",
            "PRZELEW MTRANSFER WYCHODZĄCY",
            "PRZELEW PODATKOWY",
            "PRZELEW WŁASNY"
    );

    public record StatementAccount(
            String name,
            String iban,
            String short8,
            String mask
    ) {}

    public record StatementOperation(
            int index,
            String operationDate,
            String title,
            String operationType,
            String rawDescription,
            String dstAccount,
            String accountName,
            String accountMask,
            String accountIban,
            String accountShort8,
            String mbankCategory,
            BigDecimal amount,
            String currency
    ) {}

    public record ParsedStatement(
            LocalDate fromDate,
            LocalDate toDate,
            List<StatementAccount> accounts,
            List<StatementOperation> operations
    ) {}

    public ParsedStatement parse(String html) {
        if (html == null || html.isBlank()) {
            return new ParsedStatement(null, null, List.of(), List.of());
        }

        Document doc = Jsoup.parse(html);
        String fullText = doc.text();

        LocalDate fromDate = null;
        LocalDate toDate = null;
        Matcher periodMatcher = PERIOD_PATTERN.matcher(fullText);
        if (periodMatcher.find()) {
            fromDate = LocalDate.parse(periodMatcher.group(1));
            toDate = LocalDate.parse(periodMatcher.group(2));
        }

        // Parse header accounts: <b>AccountName - 26DigitIBAN</b>
        Map<String, StatementAccount> accountsByName = new LinkedHashMap<>();
        List<StatementAccount> accounts = new ArrayList<>();
        for (Element b : doc.select("b")) {
            String text = b.text().trim();
            Matcher m = HEADER_ACCOUNT_PATTERN.matcher(text);
            if (m.matches()) {
                String name = m.group(1).trim();
                String iban = m.group(2).trim();
                String short8 = iban.substring(18);
                String mask = iban.substring(0, 4) + " ... " + iban.substring(22);
                StatementAccount acc = new StatementAccount(name, iban, short8, mask);
                accounts.add(acc);
                accountsByName.put(name.toLowerCase(), acc);
            }
        }

        // Find operations table containing "Data operacji" and "Kwota"
        List<StatementOperation> operations = new ArrayList<>();
        Elements tables = doc.select("table");
        Element operationsTable = null;
        for (Element table : tables) {
            Element headRow = table.selectFirst("tr.head");
            if (headRow != null) {
                String headText = headRow.text();
                if (headText.contains("Data operacji") && headText.contains("Kwota")) {
                    operationsTable = table;
                    break;
                }
            }
        }

        if (operationsTable != null) {
            Elements rows = operationsTable.select("tr");
            int idx = 1;
            for (Element row : rows) {
                if (row.hasClass("head")) {
                    continue;
                }
                Elements cells = row.select("td");
                if (cells.size() < 5) {
                    continue;
                }

                String opDateStr = cells.get(0).text().trim();
                if (!opDateStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    continue;
                }

                List<String> descLines = extractLines(cells.get(1));
                DescriptionParts descParts = parseDescription(descLines);

                List<String> accountLines = extractLines(cells.get(2));
                String accountName = !accountLines.isEmpty() ? accountLines.get(0).trim() : "";
                String accountMask = accountLines.size() > 1 ? accountLines.get(1).trim() : "";

                StatementAccount matchedAcc = resolveAccount(accountName, accountMask, accounts, accountsByName);
                String accountIban = matchedAcc != null ? matchedAcc.iban() : null;
                String accountShort8 = matchedAcc != null ? matchedAcc.short8() : extractLast4FromMask(accountMask);

                String mbankCategory = cells.get(3).text().trim();
                AmountCurrency amountCurrency = parseAmountAndCurrency(cells.get(4).text().trim());

                if (amountCurrency.amount() == null) {
                    continue;
                }

                operations.add(new StatementOperation(
                        idx++,
                        opDateStr,
                        descParts.title(),
                        descParts.operationType(),
                        descParts.rawDescription(),
                        descParts.dstAccount(),
                        accountName,
                        accountMask,
                        accountIban,
                        accountShort8,
                        mbankCategory,
                        amountCurrency.amount(),
                        amountCurrency.currency()
                ));
            }
        }

        // Fallback date range from operations if header period was not found
        if ((fromDate == null || toDate == null) && !operations.isEmpty()) {
            for (StatementOperation op : operations) {
                LocalDate d = LocalDate.parse(op.operationDate());
                if (fromDate == null || d.isBefore(fromDate)) {
                    fromDate = d;
                }
                if (toDate == null || d.isAfter(toDate)) {
                    toDate = d;
                }
            }
        }

        return new ParsedStatement(fromDate, toDate, accounts, operations);
    }

    private StatementAccount resolveAccount(
            String accountName,
            String accountMask,
            List<StatementAccount> accounts,
            Map<String, StatementAccount> accountsByName
    ) {
        if (accountName != null && !accountName.isBlank()) {
            StatementAccount byName = accountsByName.get(accountName.toLowerCase());
            if (byName != null) {
                return byName;
            }
        }
        if (accountMask != null && !accountMask.isBlank()) {
            Matcher m = MASK_DIGITS_PATTERN.matcher(accountMask);
            if (m.find()) {
                String first4 = m.group(1);
                String last4 = m.group(2);
                for (StatementAccount acc : accounts) {
                    if (acc.iban().startsWith(first4) && acc.iban().endsWith(last4)) {
                        return acc;
                    }
                }
            }
        }
        return null;
    }

    private String extractLast4FromMask(String accountMask) {
        if (accountMask == null) return null;
        Matcher m = MASK_DIGITS_PATTERN.matcher(accountMask);
        if (m.find()) {
            return m.group(2);
        }
        return null;
    }

    private List<String> extractLines(Element cell) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (Node node : cell.childNodes()) {
            if (node instanceof TextNode textNode) {
                current.append(textNode.getWholeText());
            } else if (node instanceof Element el) {
                if ("br".equalsIgnoreCase(el.tagName())) {
                    lines.add(current.toString().trim());
                    current.setLength(0);
                } else {
                    current.append(el.text());
                }
            }
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(current.toString().trim());
        }
        return lines;
    }

    private record DescriptionParts(
            String title,
            String operationType,
            String rawDescription,
            String dstAccount
    ) {}

    private DescriptionParts parseDescription(List<String> lines) {
        if (lines.isEmpty()) {
            return new DescriptionParts("", "", "", null);
        }

        String line1 = lines.get(0).replaceAll("\\s+", " ").trim();
        String line2Raw = lines.size() > 1 ? lines.get(1) : "";
        String line3 = lines.size() > 2 ? lines.get(2).replaceAll("\\s+", "").trim() : "";

        String dstAccount = null;
        if (IBAN_26_PATTERN.matcher(line3).matches()) {
            dstAccount = line3.substring(18);
        } else if (lines.size() > 1 && IBAN_26_PATTERN.matcher(line2Raw.replaceAll("\\s+", "")).matches()) {
            dstAccount = line2Raw.replaceAll("\\s+", "").substring(18);
            line2Raw = "";
        }

        String operationType = "";
        String detailsPart = line2Raw.trim();

        String upperLine2 = detailsPart.toUpperCase();
        for (String knownType : KNOWN_OPERATION_TYPES) {
            if (upperLine2.endsWith(knownType)) {
                operationType = knownType;
                detailsPart = detailsPart.substring(0, detailsPart.length() - knownType.length()).trim();
                break;
            }
        }

        if (operationType.isEmpty() && !detailsPart.isEmpty()) {
            // Split by large whitespace gap if present in mBank fixed-width formatting
            String[] chunks = line2Raw.trim().split("\\s{4,}");
            if (chunks.length >= 2) {
                operationType = chunks[chunks.length - 1].trim();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < chunks.length - 1; i++) {
                    if (!chunks[i].isBlank() && !chunks[i].trim().equals(".")) {
                        if (!sb.isEmpty()) sb.append(" ");
                        sb.append(chunks[i].trim());
                    }
                }
                detailsPart = sb.toString();
            } else {
                operationType = detailsPart.replaceAll("\\s+", " ");
                detailsPart = "";
            }
        }

        detailsPart = detailsPart.replaceAll("\\s+", " ").trim();
        if (detailsPart.equals(".")) {
            detailsPart = "";
        }
        if (detailsPart.endsWith(" .")) {
            detailsPart = detailsPart.substring(0, detailsPart.length() - 2).trim();
        }

        String title = line1;
        // When line 1 is just the operation type (e.g. "BLIK P2P-WYCHODZĄCY", "PRZELEW EXPRESS ELIXIR WYCH.", "mTransfer")
        // and detailsPart has the actual recipient/title, combine or promote detailsPart.
        if (!detailsPart.isEmpty()) {
            if (title.equalsIgnoreCase(operationType)
                    || title.equalsIgnoreCase("mTransfer")
                    || title.equalsIgnoreCase("BLIK WYPŁATA ATM WŁASNY")
                    || title.equalsIgnoreCase("BLIK WYPŁATA ATM KRAJOWY")) {
                title = detailsPart + " (" + title + ")";
            } else if (!detailsPart.equalsIgnoreCase(title) && !title.contains(detailsPart)) {
                // Keep line1 as primary title, append compact details if helpful
                title = line1 + " — " + detailsPart;
            }
        }

        String rawDescription = String.join(" | ", lines.stream()
                .map(s -> s.replaceAll("\\s+", " ").trim())
                .filter(s -> !s.isEmpty())
                .toList());

        return new DescriptionParts(title, operationType, rawDescription, dstAccount);
    }

    private record AmountCurrency(BigDecimal amount, String currency) {}

    private AmountCurrency parseAmountAndCurrency(String raw) {
        if (raw == null || raw.isBlank()) {
            return new AmountCurrency(null, "PLN");
        }
        String normalized = raw.replace('\u00A0', ' ').trim();
        Matcher m = AMOUNT_CURRENCY_PATTERN.matcher(normalized);
        if (m.matches()) {
            String numPart = m.group(1).replaceAll("\\s+", "").replace(',', '.');
            String currency = m.group(2) != null ? m.group(2).toUpperCase() : "PLN";
            try {
                return new AmountCurrency(new BigDecimal(numPart), currency);
            } catch (NumberFormatException ignored) {
                return new AmountCurrency(null, currency);
            }
        }
        // Fallback: strip everything except digits, minus, comma/dot
        String fallbackNum = normalized.replaceAll("[^0-9,.-]", "").replace(',', '.');
        try {
            return new AmountCurrency(new BigDecimal(fallbackNum), "PLN");
        } catch (NumberFormatException e) {
            return new AmountCurrency(null, "PLN");
        }
    }
}
