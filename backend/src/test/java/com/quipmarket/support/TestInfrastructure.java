package com.quipmarket.support;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    /** @ServiceConnection wires spring.datasource.* to the container automatically. */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(java.time.Instant.parse("2026-10-01T12:00:00Z"));
    }

    @Bean
    QueryCounter queryCounter() {
        return new QueryCounter();
    }

    /** Wraps the DataSource so tests can count SQL statements (used to prove there is no N+1). */
    @Bean
    static BeanPostProcessor countingDataSource(org.springframework.beans.factory.ObjectProvider<QueryCounter> counter) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource ds)) return bean;
                return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, args) -> {
                    Object result = m.invoke(ds, args);
                    if (result instanceof Connection c) return wrap(c, counter.getObject());
                    return result;
                });
            }
        };
    }

    private static Connection wrap(Connection c, QueryCounter counter) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (p, m, args) -> {
            if (m.getName().equals("prepareStatement")) counter.increment();
            try {
                return m.invoke(c, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    public static class QueryCounter {
        private final AtomicInteger count = new AtomicInteger();
        void increment() { count.incrementAndGet(); }
        public void reset() { count.set(0); }
        public int count() { return count.get(); }
    }
}
