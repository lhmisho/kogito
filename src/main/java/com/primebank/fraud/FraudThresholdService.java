package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class FraudThresholdService {

    @Inject
    DataSource dataSource; // provided by Quarkus datasource config

    // simple in-memory cache
    private final Map<String, BigDecimal> cache = new ConcurrentHashMap<>();
    private volatile Instant lastLoadedAt = Instant.EPOCH;

    // production default: reload every 60 seconds (tune as you like)
    private static final Duration TTL = Duration.ofSeconds(60);

    /** Load thresholds from DB if cache is empty or TTL expired. */
    public synchronized void load() {
        if (!cache.isEmpty() && Instant.now().isBefore(lastLoadedAt.plus(TTL))) {
            return; // cache still valid
        }

        Map<String, BigDecimal> fresh = new ConcurrentHashMap<>();

        String sql = "SELECT rule_key, rule_value FROM dbo.fraud_thresholds";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String key = rs.getString(1);
                BigDecimal val = rs.getBigDecimal(2);
                if (key != null && val != null) {
                    fresh.put(key.trim(), val);
                }
            }

        } catch (Exception e) {
            // if DB fails but we already have cache, keep old values
            if (!cache.isEmpty()) {
                return;
            }
            throw new IllegalStateException("Failed to load fraud thresholds from DB", e);
        }

        cache.clear();
        cache.putAll(fresh);
        lastLoadedAt = Instant.now();
    }

    /** Get a threshold value; throws if missing. */
    public BigDecimal get(String key) {
        if (key == null) throw new IllegalArgumentException("key is null");

        BigDecimal v = cache.get(key);
        if (v == null) {
            // try a forced reload once
            load();
            v = cache.get(key);
        }
        if (v == null) {
            throw new IllegalStateException("Threshold not found: " + key);
        }
        return v;
    }

    /** Optional: expose cache for debug endpoints. */
    public Map<String, BigDecimal> snapshot() {
        return new ConcurrentHashMap<>(cache);
    }
}
