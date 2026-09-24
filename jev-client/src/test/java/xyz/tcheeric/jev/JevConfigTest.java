package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevConfigTest {

    @Test
    void theDefaultModelIsPinnedToADatedVersion() {
        // A consumer calibrates thresholds against a specific model. The default must therefore
        // name a version that cannot change underneath it.
        JevConfig config = JevConfig.of(URI.create("https://jev.example"), "token");

        assertThat(config.model()).isEqualTo(JevConfig.DEFAULT_MODEL);
        assertThat(config.model()).doesNotContain("latest");
        assertThat(config.model()).matches(".*-\\d{8}$");
    }

    @Test
    void aMovingAliasIsRefusedAtConstruction() {
        // Pointing at an alias would let an upstream model swap silently change every answer a
        // consumer has already tuned against, so it fails immediately rather than in production.
        assertThatThrownBy(() -> JevConfig.of(URI.create("https://jev.example"), "token").withModel("jev-latest"))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("pinned to a version");
    }

    @Test
    void theApiTokenNeverAppearsInTheConfigsOwnToString() {
        // A record's generated toString would print the bearer token straight into any log line
        // that mentions the config. The secret must not leak that easily.
        JevConfig config = JevConfig.of(URI.create("https://jev.example"), "sk-super-secret");

        assertThat(config.toString()).doesNotContain("sk-super-secret").contains("<redacted>");
    }

    @Test
    void aBlankTokenIsRefusedRatherThanSentAsAnEmptyBearer() {
        // An empty bearer header produces a confusing 401 from the server. Failing here names
        // the real problem.
        assertThatThrownBy(() -> JevConfig.of(URI.create("https://jev.example"), "  "))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("api token");
    }

    @Test
    void aZeroTimeoutIsRefusedBecauseItWouldMeanNeverSucceed() {
        // A zero or negative timeout cancels every request the instant it starts.
        assertThatThrownBy(() -> JevConfig.of(URI.create("https://jev.example"), "token")
                .withRequestTimeout(Duration.ZERO))
                .isInstanceOf(JevException.class)
                .hasMessageContaining("requestTimeout");
    }
}
