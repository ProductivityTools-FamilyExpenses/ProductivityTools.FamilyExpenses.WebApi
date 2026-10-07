package top.productivitytools.spendings.webapi.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import top.productivitytools.spendings.webapi.dto.ParsedExpense;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class MBankEmailParser {

    public List<ParsedExpense> parseHtmlAttachment(String messageId, String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);
        String date = extractDate(doc);

        Elements trElements = doc.select("table > tbody > tr > td > table > tbody > tr");
        if (trElements.isEmpty()) {
            trElements = doc.select("table table tr");
        }

        List<List<String>> rows = new ArrayList<>();
        for (Element tr : trElements) {
            Elements tds = tr.select("td");
            List<String> row = new ArrayList<>();
            for (Element td : tds) {
                row.add(td.text().trim());
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }

        List<ParsedExpense> expenses = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.size() >= 2 && !row.get(0).startsWith("Czas")) {
                String operationId = messageId + "-" + rowIndex;
                ParsedExpense parsed = parseTransferRow(operationId, date, row.get(0), row.get(1));
                if (parsed != null) {
                    expenses.add(parsed);
                }
            }
        }

        return expenses;
    }

    String extractDate(Document doc) {
        Element header = doc.selectFirst("table > tbody > tr > td > h1, table tr td h1, h1, h5");
        if (header == null) {
            return "";
        }
        return header.text()
                .replace("Powiadomienie e-mail", "")
                .trim()
                .replace(" -", "")
                .replace("- ", "")
                .trim();
    }

    public ParsedExpense parseTransferRow(String operationId, String date, String time, String details) {
        if (details == null || details.isBlank() || !details.startsWith("mBank:")) {
            return null;
        }

        if (details.startsWith("mBank: Autoryzacja karty")) {
            String[] spaceSegments = details.split(" ");
            String srcCard = spaceSegments[3].replace(":", "").trim();

            String[] colonSegments = details.split(":");
            String name = colonSegments[2].replace(". Kwota", "").trim();

            String amountRaw = colonSegments[3].trim();
            String[] amountParts = amountRaw.split(" ");
            BigDecimal amount = parseAmount(amountParts[0]).negate();
            String currency = amountParts[1].replace(".", "");

            String amountLeftRaw = colonSegments[4].trim();
            String[] amountLeftParts = amountLeftRaw.split(" ");
            String amountLeft = amountLeftParts[0];
            String amountLeftCurrency = amountLeftParts.length > 1 ? amountLeftParts[1].replace(".", "") : "";

            return new ParsedExpense(
                    operationId, date, time, "Autoryzacja karty",
                    srcCard, "", amount, currency, name,
                    amountLeft, amountLeftCurrency, details
            );
        }

        if (details.startsWith("mBank: Przelew wych")) {
            String[] segments = details.split(" ");
            String srcAccount = segments[5];
            String dstAccount = segments[8];
            BigDecimal amount = parseAmount(segments[10]).negate();
            String currency = segments[11];

            String afterDla = details.split(Pattern.quote("dla"), 2)[1];
            String name = afterDla.split(Pattern.quote("Dost."), 2)[0].trim();

            String leftValue = "??";
            String leftCurrency = "??";
            if (details.contains("Dost.")) {
                String[] left = afterDla.split(Pattern.quote("Dost."), 2)[1].trim().split(" ");
                leftValue = left[0];
                leftCurrency = left.length > 1 ? left[1].replace(".", "") : "";
            }

            return new ParsedExpense(
                    operationId, date, time, "Przelew wychodzacy",
                    srcAccount, dstAccount, amount, currency, name,
                    leftValue, leftCurrency, details
            );
        }

        if (details.startsWith("mBank: Przelew przych")) {
            String[] segments = details.split(" ");
            String srcAccount = segments[5];
            String dstAccount = segments[8];
            BigDecimal amount = parseAmount(segments[10]);
            String currency = segments[11];

            String afterOd = details.split(Pattern.quote(" od "), 2)[1];
            String name = afterOd.split(Pattern.quote("Dost."), 2)[0].trim();

            String leftValue = "??";
            String leftCurrency = "??";
            if (afterOd.contains("Dost.")) {
                String[] left = afterOd.split(Pattern.quote("Dost."), 2)[1].trim().split(" ");
                leftValue = left[0];
                leftCurrency = left.length > 1 ? left[1].replace(".", "") : "";
            }

            return new ParsedExpense(
                    operationId, date, time, "Przelew przychodzący",
                    srcAccount, dstAccount, amount, currency, name,
                    leftValue, leftCurrency, details
            );
        }

        if (details.startsWith("mBank: Obciazenie")) {
            String[] segments = details.split(" ");
            String srcAccount = segments[3];
            BigDecimal amount = parseAmount(segments[6]).negate();
            String amountCurrency = segments[7];

            String afterTytulem = details.split(Pattern.quote("tytulem: "), 2)[1];
            String[] semiParts = afterTytulem.split(Pattern.quote(";"), 2);
            String name = semiParts[0].trim();
            String[] amountLeftRaw = semiParts[1].trim().split(" ");
            String amountLeft = amountLeftRaw[1];
            String amountLeftCurrency = amountLeftRaw[2];

            return new ParsedExpense(
                    operationId, date, time, "Obciazenie",
                    srcAccount, "", amount, amountCurrency, name,
                    amountLeft, amountLeftCurrency, details
            );
        }

        if (details.startsWith("mBank: Uznanie na rach.")) {
            String[] segments = details.split(" ");
            String dstAccount = segments[4];
            BigDecimal amount = parseAmount(segments[7]);
            String amountCurrency = segments[8];

            String afterTytulem = details.split(Pattern.quote("tytulem: "), 2)[1];
            String[] semiParts = afterTytulem.split(Pattern.quote(";"), 2);
            String name = semiParts[0].trim();
            String[] amountLeftRaw = semiParts[1].trim().split(" ");
            String amountLeft = amountLeftRaw[1];
            String amountLeftCurrency = amountLeftRaw[2];

            return new ParsedExpense(
                    operationId, date, time, "Uznanie",
                    "", dstAccount, amount, amountCurrency, name,
                    amountLeft, amountLeftCurrency, details
            );
        }

        if (details.startsWith("mBank: Odmowa autoryzacji")
                || details.startsWith("mBank: Potwierdzenie poprawnego")
                || details.startsWith("mBank: Twoj przelew do")
                || details.startsWith("mBank: Niepoprawne logowanie")) {
            return null;
        }

        throw new IllegalArgumentException("Unrecognized mBank operation format: " + details);
    }

    private BigDecimal parseAmount(String raw) {
        return new BigDecimal(raw.trim().replace(",", "."));
    }
}
