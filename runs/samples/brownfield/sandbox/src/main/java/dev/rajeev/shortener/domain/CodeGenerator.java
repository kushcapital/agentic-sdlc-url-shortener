package dev.rajeev.shortener.domain;

import java.util.function.Predicate;

public interface CodeGenerator {

    /** Returns a code the predicate reports as free, or throws {@link CodeExhaustedException}. */
    String generate(Predicate<String> exists);
}
