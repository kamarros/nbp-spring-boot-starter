package io.github.kamarros.nbp;

import java.util.List;

/**
 * Currency together with its published rates.
 *
 * @param table    NBP table type ({@code A} or {@code B})
 * @param currency currency name (in Polish, as returned by NBP)
 * @param code     ISO 4217 code, e.g. {@code EUR}
 * @param rates    rates ordered from the oldest to the newest
 */
public record CurrencyRates(String table, String currency, String code, List<Rate> rates) {

    public CurrencyRates {
        rates = List.copyOf(rates);
    }
}
