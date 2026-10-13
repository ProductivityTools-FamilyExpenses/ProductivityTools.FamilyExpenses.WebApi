package top.productivitytools.spendings.webapi.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import top.productivitytools.spendings.webapi.dto.ParsedAllegroPurchase;
import top.productivitytools.spendings.webapi.dto.ParsedAllegroRefund;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AllegroEmailParser {

    private static final ZoneId WARSAW_ZONE = ZoneId.of("Europe/Warsaw");
    /** "1 234,56" / "1234.56" / "64,58" (spaces and NBSP as thousand separators). */
    private static final String PRICE = "\\d{1,3}(?:[\\s\\u00a0]?\\d{3})*(?:[.,]\\d{2})?";

    public List<ParsedAllegroPurchase> parseEmailBody(String messageId, OffsetDateTime emailDate, String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);

        LocalDate purchaseDate = (emailDate != null)
                ? emailDate.atZoneSameInstant(WARSAW_ZONE).toLocalDate()
                : LocalDate.now(WARSAW_ZONE);

        Element totalPriceElement = doc.selectFirst("[data-cy=\"payment.buyerTotalValue\"]");
        if (totalPriceElement == null || totalPriceElement.text().isBlank()) {
            throw new IllegalArgumentException("Missing [data-cy=\"payment.buyerTotalValue\"] in Allegro email");
        }

        BigDecimal fullPrice = parsePrice(totalPriceElement.text().replace("zł", ""));

        Elements offerTables = doc.select("[data-cy=\"offers.table\"]");
        List<ParsedAllegroPurchase> purchases = new ArrayList<>();
        int itemIndex = 0;

        for (Element table : offerTables) {
            String purchaseName = "";
            for (Element td : table.select("td")) {
                String text = td.text().trim();
                if (text.isEmpty()) {
                    continue;
                }

                int indexOfZl = text.indexOf("zł");
                if (indexOfZl > -1) {
                    String costStr = text.substring(0, indexOfZl).trim();
                    BigDecimal purchaseCost = parsePrice(costStr);

                    String multipleItems = text.substring(indexOfZl)
                            .replace("\n", "")
                            .replace("\r", "")
                            .replace("zł", "")
                            .trim();

                    String multipleItemsAmount = "";
                    String multipleItemsCost = "";
                    int separatorIdx = multipleItems.indexOf("×");
                    if (separatorIdx > -1) {
                        multipleItemsAmount = multipleItems.substring(0, separatorIdx).trim();
                        multipleItemsCost = multipleItems.substring(separatorIdx + 1).trim().replace(",", ".");
                    }

                    itemIndex++;
                    String operationId = messageId + "-" + itemIndex;

                    purchases.add(new ParsedAllegroPurchase(
                            operationId,
                            purchaseDate,
                            fullPrice,
                            purchaseName,
                            purchaseCost,
                            multipleItems,
                            multipleItemsAmount,
                            multipleItemsCost
                    ));
                } else {
                    purchaseName = text;
                }
            }
        }

        return purchases;
    }

    // --- refunds -------------------------------------------------------------

    private static final Pattern REFUND_SUBJECT = Pattern.compile("^\\s*Zwrot\\b", Pattern.CASE_INSENSITIVE);
    /** "Zwrot 64,58 zł za zakup 2 przedmiotów w tym: Buty ..." */
    private static final Pattern REFUND_SUBJECT_AMOUNT = Pattern.compile("^\\s*Zwrot\\s+(" + PRICE + ")\\s*zł", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND_DESCRIPTION = Pattern.compile("za\\s+zakup\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    /** "Kwota do zwrotu: 64,58 zł (z 104,57 zł)" */
    private static final Pattern REFUND_BODY_AMOUNT = Pattern.compile("Kwota\\s+do\\s+zwrotu:?\\s*(" + PRICE + ")\\s*zł", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND_ORIGINAL_AMOUNT = Pattern.compile("\\(\\s*z\\s+(" + PRICE + ")\\s*zł\\s*\\)", Pattern.CASE_INSENSITIVE);
    /** "Sprzedający sklep_sportano zlecił częściowy zwrot za Twoje zamówienie." */
    private static final Pattern REFUND_SELLER = Pattern.compile("Sprzedaj[ąa]cy\\s+(\\S+)\\s+zleci", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUND_PARTIAL = Pattern.compile("cz[ęe][śs]ciow", Pattern.CASE_INSENSITIVE);

    public boolean isRefundSubject(String subject) {
        return subject != null && REFUND_SUBJECT.matcher(subject).find();
    }

    /**
     * Parses a refund notification. The amount comes from the subject (stable format), the body only adds
     * the original order value, seller and partial/full flag - all optional, so layout changes don't break import.
     */
    public ParsedAllegroRefund parseRefund(String messageId, OffsetDateTime emailDate, String subject, String rawHtml) {
        LocalDate refundDate = (emailDate != null)
                ? emailDate.atZoneSameInstant(WARSAW_ZONE).toLocalDate()
                : LocalDate.now(WARSAW_ZONE);
        String bodyText = rawHtml == null ? "" : Jsoup.parse(rawHtml).text();

        BigDecimal amount = firstPrice(REFUND_SUBJECT_AMOUNT, subject);
        if (amount == null) {
            amount = firstPrice(REFUND_BODY_AMOUNT, bodyText);
        }
        if (amount == null) {
            throw new IllegalArgumentException("Refund amount not found in subject nor in 'Kwota do zwrotu' (subject: " + subject + ")");
        }
        BigDecimal original = firstPrice(REFUND_ORIGINAL_AMOUNT, bodyText);
        Matcher seller = REFUND_SELLER.matcher(bodyText);
        Matcher description = REFUND_DESCRIPTION.matcher(subject == null ? "" : subject);
        boolean partial = REFUND_PARTIAL.matcher(bodyText).find()
                || (original != null && original.compareTo(amount) > 0);

        return new ParsedAllegroRefund(
                messageId + "-refund",
                refundDate,
                amount,
                original,
                partial,
                seller.find() ? seller.group(1) : null,
                description.find() ? description.group(1).trim() : subject
        );
    }

    private BigDecimal firstPrice(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher m = pattern.matcher(text);
        return m.find() ? parsePrice(m.group(1)) : null;
    }

    private BigDecimal parsePrice(String raw) {
        String cleaned = raw.replace("\u00a0", "")
                .replace(" ", "")
                .replace(",", ".")
                .trim();
        return new BigDecimal(cleaned);
    }
}
