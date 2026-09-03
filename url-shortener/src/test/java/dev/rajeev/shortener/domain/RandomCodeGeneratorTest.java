package dev.rajeev.shortener.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RandomCodeGeneratorTest {

    @Test
    void producesCodesOfTheRequestedLengthFromTheBase62Alphabet() {
        RandomCodeGenerator gen = new RandomCodeGenerator(7);
        for (int i = 0; i < 200; i++) {
            String code = gen.randomCode();
            assertEquals(7, code.length());
            for (char c : code.toCharArray()) assertTrue(RandomCodeGenerator.BASE62.indexOf(c) >= 0, "char " + c);
        }
    }

    @Test
    void rejectsBytesAbove247ToAvoidModuloBias() {
        AtomicInteger calls = new AtomicInteger();
        RandomCodeGenerator gen = new RandomCodeGenerator(4, 5, n -> {
            if (calls.incrementAndGet() == 1) return new byte[] {(byte) 255, (byte) 250, (byte) 248, 0, 1, 2, 3};
            byte[] b = new byte[n];
            java.util.Arrays.fill(b, (byte) 61);
            return b;
        });
        assertEquals("0123", gen.randomCode());
    }

    @Test
    void rejectsNonPositiveLength() {
        assertThrows(IllegalArgumentException.class, () -> new RandomCodeGenerator(0));
    }

    @Test
    void retriesOnCollisionAndReturnsTheFirstFreeCode() {
        Set<String> seen = new HashSet<>();
        AtomicInteger probes = new AtomicInteger();
        RandomCodeGenerator gen = new RandomCodeGenerator(6, 5, RandomCodeGeneratorTest::secure);
        String code = gen.generate(c -> {
            seen.add(c);
            return probes.incrementAndGet() < 3; // first two candidates "exist"
        });
        assertEquals(6, code.length());
        assertEquals(3, probes.get());
        assertEquals(3, seen.size());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        RandomCodeGenerator gen = new RandomCodeGenerator(6, 3, RandomCodeGeneratorTest::secure);
        assertThrows(CodeExhaustedException.class, () -> gen.generate(c -> true));
    }

    private static byte[] secure(int n) {
        byte[] b = new byte[n];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }
}
