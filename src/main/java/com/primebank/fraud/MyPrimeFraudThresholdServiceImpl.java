package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MyPrimeFraudThresholdServiceImpl implements MyPrimeFraudThresholdService {

    @Inject
    DataSource dataSource;

    // Cache with TTL
    private volatile Map<String, BigDecimal> thresholdCache = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> riskRulesCache = new ArrayList<>();
    private volatile LocalDateTime lastThresholdRefresh = LocalDateTime.MIN;
    private volatile LocalDateTime lastRulesRefresh = LocalDateTime.MIN;
    
    private static final int CACHE_TTL_MINUTES = 5;

    @Override
    public void loadActiveThresholds() {
        loadThresholdsWithCache();
        loadRiskRulesWithCache();
    }

    @Override
    public Number get(String key) {
        loadThresholdsWithCache();
        BigDecimal value = thresholdCache.get(key);
        if (value == null) {
            return getDefaultThreshold(key);
        }
        return value;
    }

    @Override
    public Map<String, Number> getAllThresholds() {
        loadThresholdsWithCache();
        Map<String, Number> result = new HashMap<>();
        thresholdCache.forEach((key, value) -> result.put(key, value));
        
        // Add defaults for any missing required thresholds
        addDefaultThresholdsIfMissing(result);
        
        return result;
    }

    @Override
    public Double getMlFraudThreshold() {
        return ((Number) get("ML_FRAUD_THRESHOLD")).doubleValue();
    }

    @Override
    public Double getMlSuspiciousThreshold() {
        return ((Number) get("ML_SUSPICIOUS_THRESHOLD")).doubleValue();
    }

    @Override
    public Double getUnstructuredMlFraudThreshold() {
        return ((Number) get("UNSTRUCTURED_ML_FRAUD_THRESHOLD")).doubleValue();
    }

    @Override
    public Double getUnstructuredMlSuspiciousThreshold() {
        return ((Number) get("UNSTRUCTURED_ML_SUSPICIOUS_THRESHOLD")).doubleValue();
    }

    @Override
    public Double getRiskScoreFraudThreshold() {
        return ((Number) get("RISK_SCORE_FRAUD_THRESHOLD")).doubleValue();
    }

    @Override
    public Double getRiskScoreSuspiciousThreshold() {
        return ((Number) get("RISK_SCORE_SUSPICIOUS_THRESHOLD")).doubleValue();
    }

    @Override
    public List<Map<String, Object>> getRiskRules() {
        loadRiskRulesWithCache();
        return riskRulesCache;
    }

    // Private cache management methods
    private void loadThresholdsWithCache() {
        if (ChronoUnit.MINUTES.between(lastThresholdRefresh, LocalDateTime.now()) >= CACHE_TTL_MINUTES) {
            refreshThresholds();
        }
    }

    private void loadRiskRulesWithCache() {
        if (ChronoUnit.MINUTES.between(lastRulesRefresh, LocalDateTime.now()) >= CACHE_TTL_MINUTES) {
            refreshRiskRules();
        }
    }

    private synchronized void refreshThresholds() {
        Map<String, BigDecimal> newCache = new HashMap<>();
        
        String sql = "SELECT threshold_key, threshold_value " +
            "FROM myprime_thresholds " +
            "WHERE is_active = 1 " +
            "AND (effective_to IS NULL OR effective_to > GETDATE()) ";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String key = rs.getString("threshold_key");
                BigDecimal value = rs.getBigDecimal("threshold_value");
                newCache.put(key, value);
            }

            this.thresholdCache = newCache;
            this.lastThresholdRefresh = LocalDateTime.now();
            
            System.out.println("MyPrime thresholds cache refreshed. Loaded " + 
                newCache.size() + " thresholds at " + LocalDateTime.now());

        } catch (Exception e) {
            System.err.println("Failed to refresh MyPrime thresholds: " + e.getMessage());
            // Load defaults if DB fails
            loadDefaultThresholds();
        }
    }

    private synchronized void refreshRiskRules() {
        List<Map<String, Object>> newCache = new ArrayList<>();
        
        String sql = "SELECT rule_id, rule_name, rule_description, risk_score, severity "+
            "FROM myprime_risk_rules "+
            "WHERE is_active = 1 "+
            "ORDER BY priority DESC ";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> rule = new HashMap<>();
                rule.put("rule_id", rs.getString("rule_id"));
                rule.put("rule_name", rs.getString("rule_name"));
                rule.put("rule_description", rs.getString("rule_description"));
                rule.put("risk_score", rs.getBigDecimal("risk_score"));
                rule.put("severity", rs.getString("severity"));
                newCache.add(rule);
            }

            this.riskRulesCache = Collections.unmodifiableList(newCache);
            this.lastRulesRefresh = LocalDateTime.now();
            
            System.out.println("MyPrime risk rules cache refreshed. Loaded " + 
                newCache.size() + " rules at " + LocalDateTime.now());

        } catch (Exception e) {
            System.err.println("Failed to refresh MyPrime risk rules: " + e.getMessage());
            loadDefaultRiskRules();
        }
    }

    private void loadDefaultThresholds() {
        Map<String, BigDecimal> defaults = new HashMap<>();
        
        // ML Thresholds
        defaults.put("ML_FRAUD_THRESHOLD", BigDecimal.valueOf(0.7));
        defaults.put("ML_SUSPICIOUS_THRESHOLD", BigDecimal.valueOf(0.5));
        defaults.put("UNSTRUCTURED_ML_FRAUD_THRESHOLD", BigDecimal.valueOf(0.7));
        defaults.put("UNSTRUCTURED_ML_SUSPICIOUS_THRESHOLD", BigDecimal.valueOf(0.5));
        
        // Login Rules
        defaults.put("FAILED_LOGINS_THRESHOLD", BigDecimal.valueOf(3));
        defaults.put("DEVICE_CHANGE_THRESHOLD", BigDecimal.valueOf(3));
        defaults.put("LOGIN_COUNT_THRESHOLD", BigDecimal.valueOf(20));
        
        // Beneficiary Rules
        defaults.put("BENEFICIARY_ADD_24HR_THRESHOLD", BigDecimal.valueOf(3));
        defaults.put("BENEFICIARY_ADD_7D_THRESHOLD", BigDecimal.valueOf(5));
        
        // MFS Rules
        defaults.put("MFS_TRANSACTION_30MIN_THRESHOLD", BigDecimal.valueOf(3));
        defaults.put("MFS_VELOCITY_5MIN_THRESHOLD", BigDecimal.valueOf(2));
        
        // Amount Rules
        defaults.put("ROUND_AMOUNT_THRESHOLD", BigDecimal.valueOf(50000));
        defaults.put("LIMIT_PERCENTAGE_THRESHOLD", BigDecimal.valueOf(90));
        defaults.put("DAILY_LIMIT_PERCENTAGE_THRESHOLD", BigDecimal.valueOf(95));
        
        // Velocity Rules
        defaults.put("VELOCITY_5MIN_COUNT_THRESHOLD", BigDecimal.valueOf(3));
        defaults.put("VELOCITY_30MIN_COUNT_THRESHOLD", BigDecimal.valueOf(10));
        defaults.put("RAPID_AMOUNT_5MIN_PERCENT_THRESHOLD", BigDecimal.valueOf(50));
        
        // Risk Score Thresholds
        defaults.put("RISK_SCORE_FRAUD_THRESHOLD", BigDecimal.valueOf(50));
        defaults.put("RISK_SCORE_SUSPICIOUS_THRESHOLD", BigDecimal.valueOf(30));
        
        this.thresholdCache = defaults;
        System.out.println("Loaded default MyPrime thresholds");
    }

    private void loadDefaultRiskRules() {
        List<Map<String, Object>> defaultRules = new ArrayList<>();
        
        // Add all 29 rules with default values
        String[][] rules = {
            {"RULE_1", "MULTIPLE_FAILED_LOGIN_ATTEMPTS", "Multiple failed login attempts in 1 hour", "15", "SUSPICIOUS"},
            {"RULE_2", "NEW_DEVICE_LOGIN", "Login from new/unrecognized device", "10", "SUSPICIOUS"},
            {"RULE_3", "FREQUENT_DEVICE_CHANGES", "Frequent device changes in 30 days", "12", "SUSPICIOUS"},
            {"RULE_4", "UNUSUAL_LOGIN_TIME", "Login during unusual hours (2-5 AM)", "8", "SUSPICIOUS"},
            {"RULE_5", "HIGH_LOGIN_VELOCITY", "High login velocity in 24 hours", "10", "SUSPICIOUS"},
            {"RULE_6", "RAPID_BENEFICIARY_ADDITION_24HR", "Rapid beneficiary addition in 24 hours", "20", "SUSPICIOUS"},
            {"RULE_7", "RAPID_BENEFICIARY_ADDITION_7D", "Rapid beneficiary addition in 7 days", "15", "SUSPICIOUS"},
            {"RULE_8", "NEW_BENEFICIARY_LARGE_TRANSFER", "New beneficiary with immediate large transfer", "25", "SUSPICIOUS"},
            {"RULE_9", "BENEFICIARY_CHURN_PATTERN", "Beneficiary churn (add & delete pattern)", "18", "SUSPICIOUS"},
            {"RULE_10", "FREQUENT_MFS_TRANSFERS", "Frequent MFS transfers in short period", "20", "SUSPICIOUS"},
            {"RULE_11", "LARGE_MFS_CASHOUT", "Large MFS cash-out transaction", "25", "FRAUD"},
            {"RULE_12", "HIGH_MFS_VELOCITY", "High MFS transaction velocity", "22", "SUSPICIOUS"},
            {"RULE_13", "SUSPICIOUS_ROUND_AMOUNT", "Suspicious round amount transfer", "12", "SUSPICIOUS"},
            {"RULE_14", "JUST_BELOW_LIMIT", "Transaction just below daily limit", "18", "SUSPICIOUS"},
            {"RULE_15", "DAILY_LIMIT_EXHAUSTION", "Daily limit exhaustion", "20", "SUSPICIOUS"},
            {"RULE_16", "UNUSUALLY_LARGE_TRANSACTION", "Unusually large transaction", "15", "SUSPICIOUS"},
            {"RULE_17", "HIGH_VELOCITY_5MIN", "High transaction velocity in 5 minutes", "25", "FRAUD"},
            {"RULE_18", "HIGH_VELOCITY_30MIN", "High transaction velocity in 30 minutes", "20", "FRAUD"},
            {"RULE_19", "RAPID_AMOUNT_MOVEMENT_5MIN", "Rapid amount movement in 5 minutes", "30", "FRAUD"},
            {"RULE_20", "RAPID_FUND_PASSTHROUGH", "Money in and immediately out pattern", "35", "FRAUD"},
            {"RULE_21", "IMMEDIATE_DRAIN_AFTER_CREDIT", "Large debit after credit", "30", "FRAUD"},
            {"RULE_22", "OTP_NOT_VERIFIED_LARGE_TXN", "OTP not verified for large transaction", "40", "FRAUD"},
            {"RULE_23", "OTP_BYPASS_LARGE_AMOUNT", "OTP bypass for large amount", "35", "FRAUD"},
            {"RULE_24", "NEW_ACCOUNT_LARGE_TRANSACTION", "New account with high activity", "25", "SUSPICIOUS"},
            {"RULE_25", "DORMANT_ACCOUNT_ACTIVATION", "Dormant account reactivation", "28", "SUSPICIOUS"},
            {"RULE_26", "EXTREME_AMOUNT_DEVIATION", "Extreme amount deviation from average", "30", "FRAUD"},
            {"RULE_27", "HIGH_RISK_COMBINATION_1", "New device + new beneficiary + large amount", "50", "FRAUD"},
            {"RULE_28", "HIGH_RISK_COMBINATION_2", "Multiple red flags in short time", "45", "FRAUD"},
            {"RULE_29", "ACCOUNT_TAKEOVER_PATTERN", "Failed login + new device + large transaction", "55", "FRAUD"}
        };
        
        for (String[] rule : rules) {
            Map<String, Object> ruleMap = new HashMap<>();
            ruleMap.put("rule_id", rule[0]);
            ruleMap.put("rule_name", rule[1]);
            ruleMap.put("rule_description", rule[2]);
            ruleMap.put("risk_score", BigDecimal.valueOf(Double.parseDouble(rule[3])));
            ruleMap.put("severity", rule[4]);
            defaultRules.add(ruleMap);
        }
        
        this.riskRulesCache = Collections.unmodifiableList(defaultRules);
        System.out.println("Loaded default MyPrime risk rules");
    }

    private Number getDefaultThreshold(String key) {
        Map<String, Number> defaults = Map.ofEntries(
            Map.entry("ML_FRAUD_THRESHOLD", 0.7),
            Map.entry("ML_SUSPICIOUS_THRESHOLD", 0.5),
            Map.entry("UNSTRUCTURED_ML_FRAUD_THRESHOLD", 0.7),
            Map.entry("UNSTRUCTURED_ML_SUSPICIOUS_THRESHOLD", 0.5),
            Map.entry("FAILED_LOGINS_THRESHOLD", 3.0),
            Map.entry("DEVICE_CHANGE_THRESHOLD", 3.0),
            Map.entry("LOGIN_COUNT_THRESHOLD", 20.0),
            Map.entry("BENEFICIARY_ADD_24HR_THRESHOLD", 3.0),
            Map.entry("BENEFICIARY_ADD_7D_THRESHOLD", 5.0),
            Map.entry("MFS_TRANSACTION_30MIN_THRESHOLD", 3.0),
            Map.entry("MFS_VELOCITY_5MIN_THRESHOLD", 2.0),
            Map.entry("ROUND_AMOUNT_THRESHOLD", 50000.0),
            Map.entry("LIMIT_PERCENTAGE_THRESHOLD", 90.0),
            Map.entry("DAILY_LIMIT_PERCENTAGE_THRESHOLD", 95.0),
            Map.entry("VELOCITY_5MIN_COUNT_THRESHOLD", 3.0),
            Map.entry("VELOCITY_30MIN_COUNT_THRESHOLD", 10.0),
            Map.entry("RAPID_AMOUNT_5MIN_PERCENT_THRESHOLD", 50.0),
            Map.entry("RISK_SCORE_FRAUD_THRESHOLD", 50.0),
            Map.entry("RISK_SCORE_SUSPICIOUS_THRESHOLD", 30.0)
        );
        
        return defaults.getOrDefault(key, 0.0);
    }

    private void addDefaultThresholdsIfMissing(Map<String, Number> thresholds) {
        Map<String, Number> defaults = Map.ofEntries(
            Map.entry("ML_FRAUD_THRESHOLD", 0.7),
            Map.entry("ML_SUSPICIOUS_THRESHOLD", 0.5),
            Map.entry("UNSTRUCTURED_ML_FRAUD_THRESHOLD", 0.7),
            Map.entry("UNSTRUCTURED_ML_SUSPICIOUS_THRESHOLD", 0.5),
            Map.entry("FAILED_LOGINS_THRESHOLD", 3.0),
            Map.entry("DEVICE_CHANGE_THRESHOLD", 3.0),
            Map.entry("LOGIN_COUNT_THRESHOLD", 20.0),
            Map.entry("BENEFICIARY_ADD_24HR_THRESHOLD", 3.0),
            Map.entry("BENEFICIARY_ADD_7D_THRESHOLD", 5.0),
            Map.entry("MFS_TRANSACTION_30MIN_THRESHOLD", 3.0),
            Map.entry("MFS_VELOCITY_5MIN_THRESHOLD", 2.0),
            Map.entry("ROUND_AMOUNT_THRESHOLD", 50000.0),
            Map.entry("LIMIT_PERCENTAGE_THRESHOLD", 90.0),
            Map.entry("DAILY_LIMIT_PERCENTAGE_THRESHOLD", 95.0),
            Map.entry("VELOCITY_5MIN_COUNT_THRESHOLD", 3.0),
            Map.entry("VELOCITY_30MIN_COUNT_THRESHOLD", 10.0),
            Map.entry("RAPID_AMOUNT_5MIN_PERCENT_THRESHOLD", 50.0),
            Map.entry("RISK_SCORE_FRAUD_THRESHOLD", 50.0),
            Map.entry("RISK_SCORE_SUSPICIOUS_THRESHOLD", 30.0)
        );
        
        defaults.forEach((key, value) -> {
            if (!thresholds.containsKey(key)) {
                thresholds.put(key, value);
            }
        });
    }
    
    // Method to force refresh cache
    public void refreshAllCaches() {
        refreshThresholds();
        refreshRiskRules();
    }
    
    // Get cache statistics
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("threshold_count", thresholdCache.size());
        stats.put("rules_count", riskRulesCache.size());
        stats.put("last_threshold_refresh", lastThresholdRefresh.toString());
        stats.put("last_rules_refresh", lastRulesRefresh.toString());
        stats.put("cache_ttl_minutes", CACHE_TTL_MINUTES);
        return stats;
    }
}