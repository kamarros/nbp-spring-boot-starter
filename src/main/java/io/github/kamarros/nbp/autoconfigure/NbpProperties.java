package io.github.kamarros.nbp.autoconfigure;

import io.github.kamarros.nbp.NbpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration properties, prefix {@code kamarros.nbp}. */
@ConfigurationProperties(prefix = "kamarros.nbp")
public class NbpProperties {

    /** Whether the NbpClient bean is auto-configured. */
    private boolean enabled = true;

    /** Base URL of the NBP Web API. */
    private String baseUrl = NbpClient.DEFAULT_BASE_URL;

    /** NBP table used for exchange rates: A (main currencies) or B (other currencies). */
    private String table = "A";

    /** Connect and request timeout. */
    private Duration timeout = Duration.ofSeconds(10);

    /** How many days back "or previous" lookups search for a published table (1-90). */
    private int maxLookbackDays = 10;

    /** Whether past rates are cached in memory. */
    private boolean cacheEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxLookbackDays() {
        return maxLookbackDays;
    }

    public void setMaxLookbackDays(int maxLookbackDays) {
        this.maxLookbackDays = maxLookbackDays;
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    public void setCacheEnabled(boolean cacheEnabled) {
        this.cacheEnabled = cacheEnabled;
    }
}
