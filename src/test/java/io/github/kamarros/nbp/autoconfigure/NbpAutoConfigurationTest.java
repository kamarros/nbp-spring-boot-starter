package io.github.kamarros.nbp.autoconfigure;

import io.github.kamarros.nbp.NbpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class NbpAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NbpAutoConfiguration.class));

    @Test
    void registersClientByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(NbpClient.class));
    }

    @Test
    void backsOffWhenUserDefinesOwnBean() {
        NbpClient custom = NbpClient.builder().build();

        runner.withBean(NbpClient.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(NbpClient.class);
            assertThat(context.getBean(NbpClient.class)).isSameAs(custom);
        });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("kamarros.nbp.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NbpClient.class));
    }

    @Test
    void bindsProperties() {
        runner.withPropertyValues(
                        "kamarros.nbp.table=B",
                        "kamarros.nbp.timeout=3s",
                        "kamarros.nbp.max-lookback-days=5",
                        "kamarros.nbp.cache-enabled=false")
                .run(context -> {
                    NbpProperties p = context.getBean(NbpProperties.class);
                    assertThat(p.getTable()).isEqualTo("B");
                    assertThat(p.getTimeout().toSeconds()).isEqualTo(3);
                    assertThat(p.getMaxLookbackDays()).isEqualTo(5);
                    assertThat(p.isCacheEnabled()).isFalse();
                });
    }

    @Test
    void rejectsInvalidTable() {
        runner.withPropertyValues("kamarros.nbp.table=C")
                .run(context -> assertThat(context).hasFailed());
    }
}
