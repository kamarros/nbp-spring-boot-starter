package io.github.kamarros.nbp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Single average exchange rate published by NBP.
 *
 * @param no            table number, e.g. {@code 214/A/NBP/2026}
 * @param effectiveDate date the table was published
 * @param mid           average rate: how many PLN one unit of the currency costs
 */
public record Rate(String no, LocalDate effectiveDate, BigDecimal mid) {
}
