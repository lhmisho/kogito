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
public class CardFraudThresholdServiceImpl implements CardFraudThresholdService {

    @Inject
    DataSource dataSource;

    // Cache with TTL
    private volatile Map<String, BigDecimal> thresholdCache = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastThresholdRefresh = LocalDateTime.MIN;
    
    private volatile List<String> suspiciousMccList = Collections.emptyList();
    private volatile LocalDateTime lastMccRefresh = LocalDateTime.MIN;
    
    private volatile Map<String, Map<String, String>> productMccRiskCache = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastProductMccRefresh = LocalDateTime.MIN;
    
    private volatile Map<String, String> countryRiskCache = new ConcurrentHashMap<>();
    private volatile LocalDateTime lastCountryRefresh = LocalDateTime.MIN;
    
    private static final int CACHE_TTL_MINUTES = 5;

    @Override
    public void loadActiveThresholds() {
        loadThresholdsWithCache();
    }

    @Override
    public Number get(String key) {
        loadThresholdsWithCache();
        BigDecimal value = thresholdCache.get(key);
        if (value == null) {
            // Return default values for missing thresholds
            return getDefaultThreshold(key);
        }
        return value;
    }

    @Override
    public List<String> getSuspiciousMccList() {
        loadMccRulesWithCache();
        return suspiciousMccList;
    }

    // New method to get all thresholds at once (better for DMN)
    public Map<String, Number> getAllThresholds() {
        loadThresholdsWithCache();
        Map<String, Number> result = new HashMap<>();
        thresholdCache.forEach((key, value) -> result.put(key, value));
        return result;
    }

    // New method to get product MCC risk
    public String getProductMccRisk(String productCode, String mccCode) {
        loadProductMccRulesWithCache();
        Map<String, String> productRisks = productMccRiskCache.get(productCode);
        if (productRisks != null) {
            String risk = productRisks.get(mccCode);
            if (risk != null) {
                return risk;
            }
        }
        return "NORMAL"; // Default
    }

    // New method to get country risk
    public String getCountryRisk(String countryName) {
    loadCountryRulesWithCache();
    
        if (countryName == null) {
            return "NORMAL";
        }
    
        String normalized = normalizeCountryName(countryName);
        return countryRiskCache.getOrDefault(normalized, "NORMAL");
    }

    // Private cache management methods
    private void loadThresholdsWithCache() {
        if (ChronoUnit.MINUTES.between(lastThresholdRefresh, LocalDateTime.now()) >= CACHE_TTL_MINUTES) {
            refreshThresholds();
        }
    }

    private void loadMccRulesWithCache() {
        if (ChronoUnit.MINUTES.between(lastMccRefresh, LocalDateTime.now()) >= CACHE_TTL_MINUTES) {
            refreshMccRules();
        }
    }

    private void loadProductMccRulesWithCache() {
        if (ChronoUnit.MINUTES.between(lastProductMccRefresh, LocalDateTime.now()) >= CACHE_TTL_MINUTES) {
            refreshProductMccRules();
        }
    }

    private void loadCountryRulesWithCache() {
        if (ChronoUnit.MINUTES.between(lastCountryRefresh, LocalDateTime.now()) >= 5) {
            refreshCountryRules();
        }
    }

    private synchronized void refreshThresholds() {
        Map<String, BigDecimal> newCache = new HashMap<>();
        String sql = "SELECT threshold_key, threshold_value FROM fraud_threshold_card WHERE is_active = 1 AND (effective_to IS NULL OR effective_to > GETDATE())";

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

        } catch (Exception e) {
            // Log error but don't clear cache
            System.err.println("Failed to refresh thresholds: " + e.getMessage());
        }
    }

    private synchronized void refreshMccRules() {
        List<String> newList = new ArrayList<>();
        String sql = "SELECT mcc_code FROM fraud_mcc_rule WHERE is_active = 1";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                newList.add(rs.getString("mcc_code"));
            }

            this.suspiciousMccList = Collections.unmodifiableList(newList);
            this.lastMccRefresh = LocalDateTime.now();

        } catch (Exception e) {
            System.err.println("Failed to refresh MCC rules: " + e.getMessage());
        }
    }

    private synchronized void refreshProductMccRules() {
        Map<String, Map<String, String>> newCache = new HashMap<>();
        String sql = "SELECT product_code, mcc_code, risk_level FROM fraud_mcc_product_rule WHERE is_active = 1";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String productCode = rs.getString("product_code");
                String mccCode = rs.getString("mcc_code");
                String riskLevel = rs.getString("risk_level");

                newCache.computeIfAbsent(productCode, k -> new HashMap<>())
                       .put(mccCode, riskLevel);
            }

            this.productMccRiskCache = newCache;
            this.lastProductMccRefresh = LocalDateTime.now();

        } catch (Exception e) {
            System.err.println("Failed to refresh product MCC rules: " + e.getMessage());
        }
    }

    private synchronized void refreshCountryRules() {
        Map<String, String> newCache = new HashMap<>();
        
        String sql = "SELECT UPPER(country_name) as country_name, decision_level " + 
            "FROM fraud_country_block_rule " +
            "WHERE is_active = 1 " +
            "UNION SELECT UPPER(country_code) as country_name, decision_level " +
            "FROM fraud_country_block_rule WHERE is_active = 1 ";
            

        try (Connection con = dataSource.getConnection();
            PreparedStatement ps = con.prepareStatement(sql);
            ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String countryKey = rs.getString("country_name");
                String decisionLevel = rs.getString("decision_level");
                newCache.put(countryKey, decisionLevel);
            }

            this.countryRiskCache = newCache;
            this.lastCountryRefresh = LocalDateTime.now();

        } catch (Exception e) {
            System.err.println("Failed to refresh country rules: " + e.getMessage());
        }
    }

    private Number getDefaultThreshold(String key) {
        // Default values for missing thresholds
        Map<String, Number> defaults = Map.of(
            "VELOCITY_5_COUNT", 3.0,
            "VELOCITY_5_AMOUNT", 200000.0,
            "VELOCITY_30_COUNT", 5.0,
            "VELOCITY_30_AMOUNT", 500000.0,
            "FAILED_TXN_1DAY", 2.0,
            "ML_FRAUD_THRESHOLD", 0.9,
            "WRONG_CVV_10", 2.0,
            "WRONG_PIN_10", 2.0
        );
        
        return defaults.getOrDefault(key, 0.0);
    }

    // Add method to force refresh cache (for admin operations)
    public void refreshAllCaches() {
        refreshThresholds();
        refreshMccRules();
        refreshProductMccRules();
        refreshCountryRules();
    }

    private String normalizeCountryName(String countryName) {
        if (countryName == null || countryName.trim().isEmpty()) {
            return "";
        }
        
        // Trim and convert to uppercase
        String normalized = countryName.trim().toUpperCase();
        
        // Remove extra spaces
        normalized = normalized.replaceAll("\\s+", " ");
        
        // Handle common variations
        Map<String, String> variations = Map.of(
            "BURKINA FASSO", "BURKINA FASO",
            "THE GAMBIA", "GAMBIA",
            "USA", "UNITED STATES",
            "UK", "UNITED KINGDOM",
            "UAE", "UNITED ARAB EMIRATES"
        );
        
        for (Map.Entry<String, String> entry : variations.entrySet()) {
            if (normalized.equals(entry.getKey()) || normalized.contains(entry.getKey())) {
                normalized = entry.getValue();
                break;
            }
        }
        
        return normalized;
    }
}