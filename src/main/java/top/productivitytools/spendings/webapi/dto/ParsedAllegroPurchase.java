package top.productivitytools.spendings.webapi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedAllegroPurchase(
        String operationId,
        LocalDate purchaseDate,
        BigDecimal fullPrice,
        String itemName,
        BigDecimal itemCost,
        String multipleItems,
        String itemCount,
        String itemPrice
) {
}
