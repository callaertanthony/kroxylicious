/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.netty.handler.codec.http.HttpResponseStatus;

import io.kroxylicious.proxy.config.micrometer.MicrometerConfigurationHook;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;

import static io.kroxylicious.proxy.Utils.startProxy;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(KafkaClusterExtension.class)
public class MetricsIT {

    public static final String PROXY_ADDRESS = "localhost:9192";

    @BeforeEach
    public void beforeEach() {
        Metrics.globalRegistry.clear();
    }

    public static class ConfigHook implements MicrometerConfigurationHook {

        @Override
        public void configure(MeterRegistry targetRegistry) {
            new JvmThreadMetrics().bindTo(targetRegistry);
        }
    }

    @Test
    public void shouldOfferPrometheusMetricsScrapeEndpoint(KafkaCluster cluster) throws Exception {
        String config = baseConfigBuilder(PROXY_ADDRESS, cluster.getBootstrapServers())
                .withPrometheusEndpoint().build();

        try (var proxy = startProxy(config)) {
            String counter_name = "test_metric_" + Math.abs(new Random().nextLong()) + "_total";
            Metrics.counter(counter_name).increment();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:9193/metrics")).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, ofString());
            assertResponseBodyContainsMeter(response, counter_name, "1.0");
            HttpRequest notFoundReq = HttpRequest.newBuilder(URI.create("http://localhost:9193/nonexistant")).GET().build();
            HttpResponse<String> notFoundResp = HttpClient.newHttpClient().send(notFoundReq, ofString());
            assertEquals(notFoundResp.statusCode(), HttpResponseStatus.NOT_FOUND.code());
        }
    }

    @Test
    public void shouldOfferPrometheusMetricsWithNamedBinder(KafkaCluster cluster) throws Exception {
        String config = baseConfigBuilder(PROXY_ADDRESS, cluster.getBootstrapServers())
                .withMicrometerBinder("JvmGcMetrics")
                .withPrometheusEndpoint().build();

        try (var proxy = startProxy(config)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:9193/metrics")).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, ofString());
            assertResponseBodyContainsMeter(response, "jvm_gc_memory_allocated_bytes_total");
        }
    }

    @Test
    public void shouldOfferPrometheusMetricsWithCommonTags(KafkaCluster cluster) throws Exception {
        String config = baseConfigBuilder(PROXY_ADDRESS, cluster.getBootstrapServers())
                .withMicrometerCommonTag("a", "b")
                .withPrometheusEndpoint().build();

        try (var proxy = startProxy(config)) {
            String counter_name = "test_metric_" + Math.abs(new Random().nextLong()) + "_total";
            Metrics.counter(counter_name).increment();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:9193/metrics")).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, ofString());
            assertResponseBodyContainsMeterWithTag(response, counter_name, "a", "b");
        }
    }

    @Test
    public void shouldOfferPrometheusMetricsWithConfigHook(KafkaCluster cluster) throws Exception {
        String config = baseConfigBuilder(PROXY_ADDRESS, cluster.getBootstrapServers())
                .withMicrometerConfigHook(ConfigHook.class.getTypeName())
                .withPrometheusEndpoint().build();

        try (var proxy = startProxy(config)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:9193/metrics")).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, ofString());
            assertResponseBodyContainsMeter(response, "jvm_threads_live_threads");
        }
    }

    @Test
    public void shouldOfferPrometheusMetricsWithFullyQualifiedBinder(KafkaCluster cluster) throws Exception {
        String config = baseConfigBuilder(PROXY_ADDRESS, cluster.getBootstrapServers())
                .withMicrometerBinder("io.micrometer.core.instrument.binder.system.UptimeMetrics")
                .withPrometheusEndpoint().build();

        try (var proxy = startProxy(config)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:9193/metrics")).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, ofString());
            assertResponseBodyContainsMeter(response, "process_uptime_seconds");
        }
    }

    private static void assertResponseBodyContainsMeter(HttpResponse<String> response, String meterName, String meterValue) {
        assertResponseBodyContainsMeter(response, meterName, "\\{*.*\\}*", Pattern.quote(meterValue));
    }

    private static void assertResponseBodyContainsMeter(HttpResponse<String> response, String meterName) {
        assertResponseBodyContainsMeter(response, meterName, "\\{*.*\\}*", "[0-9\\.E]+");
    }

    private static void assertResponseBodyContainsMeterWithTag(HttpResponse<String> response, String meterName, String tagKey, String tagValue) {
        assertResponseBodyContainsMeter(response, meterName, "\\{" + Pattern.quote(tagKey) + "=\"" + Pattern.quote(tagValue) + "\",\\}", "[0-9\\.E]+");
    }

    private static void assertResponseBodyContainsMeter(HttpResponse<String> response, String meterName, String labelRegex, String valueRegex) {
        assertTrue(Arrays.stream(response.body().split("\n")).anyMatch(it -> it.matches(meterName + labelRegex + " " + valueRegex)));
    }

    private static KroxyConfigBuilder baseConfigBuilder(String proxyAddress, String bootstrapServers) {
        return new KroxyConfigBuilder(proxyAddress)
                .withDefaultCluster(bootstrapServers)
                .addFilter("ApiVersions")
                .addFilter("BrokerAddress");
    }
}