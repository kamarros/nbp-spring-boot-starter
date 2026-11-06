package io.github.kamarros.nbp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Price of 1 g of gold (fineness 1000) calculated by NBP.
 *
 * @param date  date of the quotation
 * @param price price in PLN
 */
public record GoldPrice(LocalDate date, BigDecimal price) {
}
