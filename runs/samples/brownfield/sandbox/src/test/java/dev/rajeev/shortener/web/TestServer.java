package dev.rajeev.shortener.web;

import dev.rajeev.shortener.UrlShortenerApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the real application (real Tomcat, real H2, real filters) on a random port and talks to it
 * over HTTP. Black-box by design: these are the tests the orchestrator runs as its exit gate, so they
 * must exercise exactly what a client sees.
 */
public final class TestServer implements AutoCloseable {

    public final ConfigurableApplicationContext ctx;
    final int port;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public TestServer(String... extraArgs) {
        // Later arguments override earlier ones (Spring would otherwise merge repeated options into a list).
        Map<String, String> args = new LinkedHashMap<>();
        for (String a : List.of(
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--shortener.base-url=http://short.test",
                "--shortener.rate-limit.max=100000",
                "--logging.level.root=WARN")) {
            args.put(a.substring(0, a.indexOf('=')), a);
        }
        for (String a : extraArgs) args.put(a.substring(0, a.indexOf('=')), a);
        ctx = SpringApplication.run(UrlShortenerApplication.class, args.values().toArray(String[]::new));
        port = ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
    }

    record Resp(int status, String body, Map<String, List<String>> headers) {
        String header(String name) {
            List<String> v = headers.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    Resp send(String method, String path, String jsonBody, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (jsonBody != null) b.header("Content-Type", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
            b.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(jsonBody));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers().map());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    Resp get(String path, String... headers) { return send("GET", path, null, headers); }
    Resp post(String path, String body, String... headers) { return send("POST", path, body, headers); }
    Resp delete(String path) { return send("DELETE", path, null); }

    @SuppressWarnings("unchecked")
    Map<String, Object> json(Resp r) {
        try {
            return json.readValue(r.body, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("not JSON: " + r.body, e);
        }
    }

    String create(String url) {
        Resp r = post("/api/links", "{\"url\":\"" + url + "\"}");
        if (r.status != 201) throw new IllegalStateException("create failed: " + r.status + " " + r.body);
        return (String) json(r).get("code");
    }

    @Override
    public void close() {
        ctx.close();
    }
}
