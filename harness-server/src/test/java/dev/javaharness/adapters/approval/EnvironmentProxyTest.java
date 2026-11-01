package dev.javaharness.adapters.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentProxyTest {
    @Test
    void usesHttpsProxyAndHonoursNoProxyAndLoopback() {
        var selector = EnvironmentProxy.from(
                Map.of("HTTPS_PROXY", "http://127.0.0.1:7890", "NO_PROXY", "localhost,.internal,example.org"));
        Proxy proxy = selector.select(URI.create("https://api.typesafe.ai/v1/systemone")).getFirst();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);
        assertThat(((InetSocketAddress) proxy.address()).getPort()).isEqualTo(7890);
        assertThat(selector.select(URI.create("http://127.0.0.1:1234/x")).getFirst()).isEqualTo(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://jev.internal/x")).getFirst()).isEqualTo(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://api.example.org/x")).getFirst()).isEqualTo(Proxy.NO_PROXY);
        assertThat(selector.select(URI.create("https://example.com/x")).getFirst()).isNotEqualTo(Proxy.NO_PROXY);
    }

    @Test
    void credentialsOrMissingVariablesFallBackToTheJvmDefault() {
        var selector = EnvironmentProxy.from(Map.of("https_proxy", "http://user:secret@proxy:8080"));
        assertThat(selector).isNotInstanceOf(EnvironmentProxy.class);
        assertThat(EnvironmentProxy.from(Map.of())).isNotInstanceOf(EnvironmentProxy.class);
        assertThat(EnvironmentProxy.from(Map.of("https_proxy", "proxy.local:3128"))).isInstanceOf(EnvironmentProxy.class);
    }
}
