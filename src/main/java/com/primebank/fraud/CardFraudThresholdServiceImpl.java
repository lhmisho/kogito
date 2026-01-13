package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class CardFraudThresholdServiceImpl
        implements CardFraudThresholdService {

    @Inject
    DataSource dataSource;

    /** Numeric thresholds cache */
    private volatile Map<String, BigDecimal> thresholdCache = new HashMap<>();

    /** MCC list cache */
    private volatile List<String> suspiciousMccList = List.of();

    @Override
    public void loadActiveThresholds() {

        Map<String, BigDecimal> thresholds = new HashMap<>();
        List<String> mccs = new ArrayList<>();

        String sql =
            "SELECT threshold_key, threshold_value, threshold_type " +
            "FROM fraud_threshold_card " +
            "WHERE is_active = 1 " +
            "AND (effective_to IS NULL OR effective_to > GETDATE())";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String key = rs.getString("threshold_key");
                BigDecimal value = rs.getBigDecimal("threshold_value");
                String type = rs.getString("threshold_type");

                // MCC rules → collect as list
                if ("MCC".equalsIgnoreCase(type)) {
                    mccs.add(key);
                } else {
                    thresholds.put(key, value);
                }
            }

            this.thresholdCache = thresholds;
            this.suspiciousMccList = mccs;

        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load Card fraud thresholds", e);
        }
    }

    @Override
    public Number get(String key) {
        BigDecimal v = thresholdCache.get(key);
        if (v == null) {
            throw new IllegalStateException(
                "Missing Card threshold: " + key);
        }
        return v;
    }

    @Override
    public List<String> getSuspiciousMccList() {
        return suspiciousMccList;
    }
}
