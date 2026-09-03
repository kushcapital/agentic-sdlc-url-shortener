package dev.rajeev.shortener.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClickNormalizerTest {

    @Test
    void referrerKeepsOnlyTheHost() {
        assertEquals("news.ycombinator.com", ClickNormalizer.referrer("https://News.YCombinator.com/item?id=1"));
        assertEquals("(direct)", ClickNormalizer.referrer(null));
        assertEquals("(direct)", ClickNormalizer.referrer(""));
        assertEquals("(invalid)", ClickNormalizer.referrer("not a url"));
    }

    @ParameterizedTest
    @CsvSource({
        "curl/8.0, curl",
        "'Mozilla/5.0 (X11) Chrome/120.0 Safari/537.36', Chrome",
        "Mozilla/5.0 Firefox/121.0, Firefox",
        "'Mozilla/5.0 (Macintosh) Version/17 Safari/605.1', Safari",
        "Mozilla/5.0 Chrome/120 Edg/120, Edge",
        "Googlebot/2.1, Bot",
        "Java-http-client/21, Java",
        "SomethingElse/1.0, Other"
    })
    void userAgentFamilies(String ua, String family) {
        assertEquals(family, ClickNormalizer.userAgent(ua));
    }

    @Test
    void unknownUserAgent() {
        assertEquals("(unknown)", ClickNormalizer.userAgent(null));
    }
}
