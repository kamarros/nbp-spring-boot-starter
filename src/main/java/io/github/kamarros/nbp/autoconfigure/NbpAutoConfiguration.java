package io.github.kamarros.nbp.autoconfigure;

import io.github.kamarros.nbp.NbpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers an {@link NbpClient} bean. Define your own bean to replace it, or set
 * {@code kamarros.nbp.enabled=false} to switch the auto-configuration off.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kamarros.nbp", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(NbpProperties.class)
public class NbpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NbpClient nbpClient(NbpProperties properties) {
        return NbpClient.builder()
                .baseUrl(properties.getBaseUrl())
                .table(properties.getTable())
                .timeout(properties.getTimeout())
                .maxLookbackDays(properties.getMaxLookbackDays())
                .cacheEnabled(properties.isCacheEnabled())
                .build();
    }
}
