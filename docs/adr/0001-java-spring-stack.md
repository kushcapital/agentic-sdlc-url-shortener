# ADR-0001: Java 21 / Spring Boot 3.4 for both modules, with a Maven-free verification path

**Status:** accepted · **Date:** 2026-09-03

## Context
The target organisation's house stack is Java / Spring on cloud platforms, and the assignment is judged on engineering quality in that context. The environment in which this submission was built had no access to Maven Central (HTTP 403 through its egress proxy), so `mvn` could not resolve dependencies there. Shipping code that had never been compiled or tested was not acceptable either.

## Decision
- Both modules are Java 21 / Spring Boot 3.4.4, built with Maven. The root `pom.xml` is a plain aggregator; each module inherits `spring-boot-starter-parent` directly so a module builds standalone if copied out.
- Verification in the build environment used the exact Spring Boot 3.4.4 dependency set (extracted from a Spring Boot 3.4.4 fat jar), H2 2.3.232, Jakarta Validation 3.0.2 and JUnit 5.10: `javac -parameters -Xlint:all` plus the JUnit Platform console launcher. That path is scripted as `scripts/verify-without-maven.sh` and kept as a documented fallback.
- The engine's sandbox toolchain is an interface with two implementations: `MavenToolchain` (the default on a normal machine) and `JavacToolchain` (fast inner loop; what every committed sample run used).
- The service uses `JdbcTemplate`, not JPA: a two-table schema with one hot batch-insert path does not earn an ORM, and explicit SQL keeps the H2/PostgreSQL common subset visible.

## Consequences
- Every class and every test in the repository was compiled and executed against the real Spring Boot 3.4.4 classes before delivery; `mvn verify` itself was first run by the reviewer (and the author, on a machine with registry access). If a Maven-specific issue surfaces, it is confined to the build description.
- Bean Validation had no provider in the build environment, so `LinkService` validates explicitly and the `@Valid` path maps to the same `400 VALIDATION` response; both paths exist and are equivalent.
- Java 21 features are used where they buy something concrete: records for artifacts and DTOs, sealed results (`UrlPolicy.Result`), exhaustive `switch` (the brownfield scenario's compile failure is this feature doing its job), virtual threads for parallel stages.
