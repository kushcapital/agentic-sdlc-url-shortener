package demo;

import java.security.SecureRandom;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Random base62 codes with rejection sampling (bytes >= 248 are discarded so every character is
 * equally likely). Random rather than sequential so codes cannot be enumerated.
 */
public final class Codes {

    public static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {}

    public static String random(int length) {
        return random(length, n -> {
            byte[] b = new byte[n];
            RANDOM.nextBytes(b);
            return b;
        });
    }

    public static String random(int length, IntFunction<byte[]> bytes) {
        if (length < 1) throw new IllegalArgumentException("length must be >= 1");
        StringBuilder out = new StringBuilder(length);
        while (out.length() < length) {
            for (byte b : bytes.apply(length * 2)) {
                int v = b & 0xFF;
                if (v < 248) {
                    out.append(BASE62.charAt(v % 62));
                    if (out.length() == length) break;
                }
            }
        }
        return out.toString();
    }

    /** Generate a code that {@code exists} reports as free; bounded so a full keyspace can't spin forever. */
    public static String unique(Predicate<String> exists, int length, int maxAttempts) {
        for (int i = 0; i < maxAttempts; i++) {
            String c = random(length);
            if (!exists.test(c)) return c;
        }
        throw new IllegalStateException("no free code after " + maxAttempts + " attempts");
    }
}
