package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Acceptance tests for the v0 URL shortener, written before the implementation (TDD red). Black-box over HTTP. */
class AppTest {

    static ConfigurableApplicationContext ctx;
    static String base;
    static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        ctx = SpringApplication.run(App.class, "--server.port=0", "--app.base-url=http://short.test", "--logging.level.root=WARN");
        base = "http://localhost:" + ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    static HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(HttpResponse<String> r) throws Exception {
        return json.readValue(r.body(), Map.class);
    }

    @Test
    void ac1_postCreatesASevenCharBase62ShortLink() throws Exception {
        HttpResponse<String> r = post("/api/links", "{\"url\":\"https://example.com/a\"}");
        assertEquals(201, r.statusCode(), r.body());
        String code = (String) body(r).get("code");
        assertTrue(code.matches("^[0-9A-Za-z]{7}$"), code);
        assertEquals("http://short.test/" + code, body(r).get("shortUrl"));
    }

    @Test
    void ac2_rejectsNonHttpAndMalformedUrlsWith400() throws Exception {
        for (String url : new String[] {"javascript:alert(1)", "ftp://x", "not a url", ""}) {
            assertEquals(400, post("/api/links", "{\"url\":\"" + url + "\"}").statusCode(), url);
        }
    }

    @Test
    void ac3_getCodeRedirectsWith302ToTheTarget() throws Exception {
        String code = (String) body(post("/api/links", "{\"url\":\"https://example.com/r\"}")).get("code");
        HttpResponse<String> r = get("/" + code);
        assertEquals(302, r.statusCode());
        assertEquals("https://example.com/r", r.headers().firstValue("Location").orElse(null));
    }

    @Test
    void ac4_unknownCodesReturn404() throws Exception {
        assertEquals(404, get("/zzzzzzz").statusCode());
    }

    @Test
    void ac5_statsCountClicksAndReportLastClickTime() throws Exception {
        String code = (String) body(post("/api/links", "{\"url\":\"https://example.com/s\"}")).get("code");
        get("/" + code);
        get("/" + code);
        HttpResponse<String> r = get("/api/links/" + code + "/stats");
        assertEquals(200, r.statusCode());
        assertEquals(2, body(r).get("clicks"));
        assertNotNull(body(r).get("lastClickAt"));
    }

    @Test
    void ac6_healthReportsOk() throws Exception {
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        assertEquals("ok", body(r).get("status"));
    }
}
