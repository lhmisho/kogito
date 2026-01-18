package com.primebank.fraud;

import io.quarkus.runtime.StartupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class MyPrimeThresholdService {
    
    private static final Logger LOG = LoggerFactory.getLogger(MyPrimeThresholdService.class);
    
    @Inject
    DataSource dataSource;
    
    private final Map<String, Object> thresholds = new HashMap<>();
    
    void onStart(@Observes StartupEvent ev) {
        loadThresholdsFromDatabase();
    }
    
    public void loadThresholdsFromDatabase() {
        LOG.info("Loading MyPrime thresholds from database...");
        
        String sql = "SELECT threshold_key, threshold_value, data_type " +
            "FROM myprime_thresholds "+
            "WHERE is_active = 1 ";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            thresholds.clear();
            int count = 0;
            
            while (rs.next()) {
                String key = rs.getString("threshold_key");
                String value = rs.getString("threshold_value");
                String dataType = rs.getString("data_type");
                
                Object parsedValue = parseValue(value, dataType);
                thresholds.put(key, parsedValue);
                count++;
                
                LOG.debug("Loaded threshold: {} = {} ({})", key, value, dataType);
            }
            
            LOG.info("Loaded {} MyPrime thresholds from database", count);
            
            // Set defaults if DB is empty
            if (thresholds.isEmpty()) {
                setDefaultThresholds();
                LOG.warn("No thresholds found in DB, using defaults");
            }
            
        } catch (SQLException e) {
            LOG.error("Failed to load thresholds from database, using defaults", e);
            setDefaultThresholds();
        }
    }
    
    private Object parseValue(String value, String dataType) {
        if (value == null) return null;
        
        String type = dataType.toUpperCase();
        
        try {
            if (type.equals("NUMBER") || type.equals("DOUBLE") || type.equals("FLOAT")) {
                return Double.parseDouble(value);
            } else if (type.equals("INTEGER") || type.equals("INT")) {
                return Integer.parseInt(value);
            } else if (type.equals("BOOLEAN") || type.equals("BOOL")) {
                return Boolean.parseBoolean(value);
            } else if (type.equals("STRING") || type.equals("TEXT")) {
                return value;
            } else {
                return value;
            }
        } catch (NumberFormatException e) {
            LOG.warn("Failed to parse threshold value '{}' as type '{}', using as string", value, dataType);
            return value;
        }
    }
    
    private void setDefaultThresholds() {
        // ML Score thresholds
        thresholds.put("ML_FRAUD_THRESHOLD", 0.7);
        thresholds.put("ML_SUSPICIOUS_THRESHOLD", 0.5);
        
        // Transaction amount thresholds
        thresholds.put("LARGE_AMOUNT_THRESHOLD", 100000.0);
        thresholds.put("VERY_LARGE_AMOUNT_THRESHOLD", 500000.0);
        
        // Failed login thresholds
        thresholds.put("FAILED_LOGINS_SUSPICIOUS_THRESHOLD", 3);
        thresholds.put("FAILED_LOGINS_FRAUD_THRESHOLD", 10);
        
        // Velocity thresholds (transactions per time window)
        thresholds.put("TX_COUNT_1HR_THRESHOLD", 5);
        thresholds.put("TX_AMOUNT_1HR_THRESHOLD", 100000.0);
        thresholds.put("TX_COUNT_24HR_THRESHOLD", 20);
        thresholds.put("TX_AMOUNT_24HR_THRESHOLD", 500000.0);
        
        // Geographic thresholds
        thresholds.put("MAX_COUNTRIES_24HR", 3);
        thresholds.put("MAX_CITIES_24HR", 5);
        
        // Device/Beneficiary thresholds
        thresholds.put("MAX_NEW_DEVICES_7DAYS", 2);
        thresholds.put("MAX_NEW_BENEFICIARIES_7DAYS", 3);
        
        // Time-based thresholds
        thresholds.put("ODD_HOURS_START", 22); // 10 PM
        thresholds.put("ODD_HOURS_END", 6);    // 6 AM
        
        LOG.info("Set {} default thresholds", thresholds.size());
    }
    
    public Object get(String key) {
        return thresholds.get(key);
    }
    
    public Double getAsDouble(String key) {
        Object value = thresholds.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }
    
    public Integer getAsInteger(String key) {
        Object value = thresholds.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }
    
    public Boolean getAsBoolean(String key) {
        Object value = thresholds.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return false;
    }
    
    public String getAsString(String key) {
        Object value = thresholds.get(key);
        return value != null ? value.toString() : null;
    }
    
    public Map<String, Object> getAllThresholds() {
        return new HashMap<>(thresholds);
    }
    
    @javax.transaction.Transactional
    public void updateThreshold(String key, Object value) {
        thresholds.put(key, value);
        // TODO: Also update database
    }
    
    @javax.transaction.Transactional
    public void reloadThresholds() {
        loadThresholdsFromDatabase();
    }
}