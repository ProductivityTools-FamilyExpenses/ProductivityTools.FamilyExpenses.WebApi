package top.productivitytools.spendings.webapi.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import top.productivitytools.spendings.webapi.dto.ParsedAllegroPurchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class AllegroEmailParser {

    private static final ZoneId WARSAW_ZONE = ZoneId.of("Europe/Warsaw");

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

    private BigDecimal parsePrice(String raw) {
        String cleaned = raw.replace("\u00a0", "")
                .replace(" ", "")
                .replace(",", ".")
                .trim();
        return new BigDecimal(cleaned);
    }
}
