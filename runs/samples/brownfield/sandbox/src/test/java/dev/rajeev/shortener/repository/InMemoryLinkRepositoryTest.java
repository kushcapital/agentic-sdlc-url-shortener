package dev.rajeev.shortener.repository;

class InMemoryLinkRepositoryTest extends LinkRepositoryContractTest {
    @Override
    protected LinkRepository createRepository() {
        return new InMemoryLinkRepository();
    }
}
