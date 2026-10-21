package com.crm.provider.domain.service;

@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
}
