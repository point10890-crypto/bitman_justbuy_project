package com.bitman.justbuy.service;

import com.bitman.justbuy.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeAiConfigIntegrityTest {
    @TempDir Path dir;
    @Test void runtimeSaveRejectsCredentialDestinationsOutsideProvider() {
        var config = new RuntimeAiConfigService(new AiProperties("", ""), new ObjectMapper(), dir.toString());
        assertThatThrownBy(() -> config.saveDeepSeekConfig("fixture-key", "http://attacker.example", "fixture")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.saveDeepSeekConfig("fixture-key", "https://attacker.example", "fixture")).isInstanceOf(IllegalArgumentException.class);
    }
}
