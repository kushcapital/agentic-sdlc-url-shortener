package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodesTest {

    @Test
    void emitsBase62CodesOfTheRequestedLength() {
        for (int i = 0; i < 100; i++) {
            String c = Codes.random(7);
            assertEquals(7, c.length());
            for (char ch : c.toCharArray()) assertTrue(Codes.BASE62.indexOf(ch) >= 0);
        }
    }

    @Test
    void skipsBiasedBytes() {
        AtomicInteger calls = new AtomicInteger();
        String c = Codes.random(3, n -> {
            if (calls.incrementAndGet() == 1) return new byte[] {(byte) 255, (byte) 250, 0, 1, 2};
            byte[] b = new byte[n];
            java.util.Arrays.fill(b, (byte) 3);
            return b;
        });
        assertEquals("012", c);
    }

    @Test
    void retriesOnCollisionAndGivesUpAfterMaxAttempts() {
        AtomicInteger probes = new AtomicInteger();
        String code = Codes.unique(c -> probes.incrementAndGet() < 3, 7, 5);
        assertEquals(7, code.length());
        assertEquals(3, probes.get());
        assertThrows(IllegalStateException.class, () -> Codes.unique(c -> true, 7, 2));
    }
}
