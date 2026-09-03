package dev.rajeev.shortener.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlPolicyTest {

    private final UrlPolicy policy = new UrlPolicy(2048, List.of("short.example"), false);

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/path?x=1", "http://example.com", "https://sub.domain.example.org:8443/a/b#frag"})
    void acceptsPublicHttpUrls(String url) {
        assertInstanceOf(UrlPolicy.Ok.class, policy.evaluate(url));
    }

    @Test
    void normalisesABareHostWithATrailingSlash() {
        assertEquals(new UrlPolicy.Ok("https://example.com/"), policy.evaluate("https://Example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url", "example.com", "//example.com", "http:///nohost"})
    void rejectsMalformedAsInvalidUrl(String url) {
        UrlPolicy.Result r = policy.evaluate(url);
        assertInstanceOf(UrlPolicy.Rejected.class, r);
        assertEquals(ErrorCode.INVALID_URL, ((UrlPolicy.Rejected) r).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)", "data:text/html,hi", "ftp://example.com/file", "file:///etc/passwd",
        "http://user:pw@example.com", "http://localhost:3000/x", "http://127.0.0.1/", "http://10.0.0.5/admin",
        "http://169.254.169.254/latest/meta-data", "http://192.168.1.1/", "http://172.20.0.1/", "http://[::1]/",
        "http://metadata.google.internal/", "http://foo.internal/", "http://100.64.0.1/", "https://short.example/abc"
    })
    void rejectsDisallowedAsUrlNotAllowed(String url) {
        UrlPolicy.Result r = policy.evaluate(url);
        assertInstanceOf(UrlPolicy.Rejected.class, r, url);
        assertEquals(ErrorCode.URL_NOT_ALLOWED, ((UrlPolicy.Rejected) r).code(), url);
    }

    @Test
    void allowsPrivateNetworksWhenExplicitlyEnabled() {
        assertInstanceOf(UrlPolicy.Ok.class, new UrlPolicy(2048, List.of(), true).evaluate("http://10.0.0.5/"));
    }

    @Test
    void enforcesTheMaximumLength() {
        assertInstanceOf(UrlPolicy.Rejected.class, policy.evaluate("https://example.com/" + "a".repeat(3000)));
    }

    @Test
    void classifiesPrivateAddressesWithoutDns() {
        for (String ip : List.of("10.1.2.3", "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fc00::1", "fd12::1")) {
            assertTrue(UrlPolicy.isPrivateAddress(ip), ip);
        }
        for (String ip : List.of("8.8.8.8", "172.32.0.1", "172.15.0.1", "1.1.1.1", "2001:db8::1")) {
            assertFalse(UrlPolicy.isPrivateAddress(ip), ip);
        }
    }
}
