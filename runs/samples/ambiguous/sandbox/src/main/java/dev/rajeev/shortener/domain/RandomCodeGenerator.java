package dev.rajeev.shortener.domain;

import java.security.SecureRandom;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Random base62 codes with a collision check (ADR-0002).
 *
 * Not a base62-encoded counter: counters are enumerable, and a shortener that lets anyone walk
 * /1, /2, /3 leaks every link ever created. 62^7 ≈ 3.5e12 keys keeps collisions rare enough that
 * the bounded retry below almost never spins; the database primary key is the real guarantee.
 *
 * Rejection sampling: 248 = 62 * 4 is the largest multiple of 62 below 256, so bytes >= 248 are
 * discarded instead of introducing modulo bias.
 */
public class RandomCodeGenerator implements CodeGenerator {

    public static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final int length;
    private final int maxAttempts;
    private final IntFunction<byte[]> random;

    public RandomCodeGenerator(int length) {
        this(length, 5, RandomCodeGenerator::secureBytes);
    }

    public RandomCodeGenerator(int length, int maxAttempts, IntFunction<byte[]> random) {
        if (length < 1) throw new IllegalArgumentException("code length must be >= 1");
        this.length = length;
        this.maxAttempts = maxAttempts;
        this.random = random;
    }

    private static final SecureRandom SECURE = new SecureRandom();

    private static byte[] secureBytes(int n) {
        byte[] b = new byte[n];
        SECURE.nextBytes(b);
        return b;
    }

    public String randomCode() {
        StringBuilder out = new StringBuilder(length);
        while (out.length() < length) {
            for (byte b : random.apply(length * 2)) {
                int v = b & 0xFF;
                if (v < 248) {
                    out.append(BASE62.charAt(v % 62));
                    if (out.length() == length) break;
                }
            }
        }
        return out.toString();
    }

    @Override
    public String generate(Predicate<String> exists) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String candidate = randomCode();
            if (!exists.test(candidate)) return candidate;
        }
        throw new CodeExhaustedException(maxAttempts);
    }
}
