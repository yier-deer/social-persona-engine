package com.socialpersona.config.controller;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderTlsTest {
    @TempDir Path directory;

    @Test
    void selfSignedProviderCertificateIsRejectedBeforeSendingApiKey() throws Exception {
        Path store = directory.resolve("test-server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process generator = new ProcessBuilder(keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "test-changeit",
                "-keypass", "test-changeit", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertTrue(generator.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, generator.exitValue());
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keyStore.load(input, "test-changeit".toCharArray()); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "test-changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            ProviderController controller = new ProviderController();
            Map<String, Object> response = controller.testConnection(Map.of(
                    "provider", "custom", "apiKey", "test-tls-key", "model", "test-model",
                    "baseUrl", "https://localhost:" + server.getAddress().getPort() + "/v1"));
            assertEquals(false, response.get("success"));
            assertEquals(0, requests.get(), "Untrusted TLS must be rejected before an authenticated HTTP request");
        } finally {
            server.stop(0);
        }
    }
}
