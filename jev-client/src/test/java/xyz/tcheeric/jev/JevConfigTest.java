package xyz.tcheeric.jev;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevConfigTest {

    @Test
    void theDefaultModelIsPinnedToTheCurrentVersionedRelease() {
        // A consumer calibrates thresholds against a specific model. The default must therefore
        // name a version that cannot change underneath it, and one the API actually serves.
        JevConfig config = JevConfig.of("token");

        assertThat(config.model()).isEqualTo("jev-1.13.0");
        assertThat(config.model()).matches("jev-\\d+\\.\\d+\\.\\d+");
        assertThat(config.baseUri()).isEqualTo(URI.create("https://api.typesafe.ai"));
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
    void thePreviewAliasIsRefusedToo() {
        // jev-preview moves ahead of jev-latest whenever a preview build ships, which makes it
        // the more volatile of the two aliases, not an exception to the rule.
        assertThatThrownBy(() -> JevConfig.of("token").withModel("jev-preview"))
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
