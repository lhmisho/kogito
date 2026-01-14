package com.primebank.fraud;

import java.util.List;
import java.util.Map;

public interface MyPrimeFraudThresholdService {
    
    /**
     * Load all active MyPrime thresholds into memory
     */
    void loadActiveThresholds();
    
    /**
     * Fetch a numeric threshold by key
     */
    Number get(String thresholdKey);
    
    /**
     * Get all thresholds as a map
     */
    Map<String, Number> getAllThresholds();
    
    /**
     * Get ML fraud threshold
     */
    Double getMlFraudThreshold();
    
    /**
     * Get ML suspicious threshold
     */
    Double getMlSuspiciousThreshold();
    
    /**
     * Get unstructured ML fraud threshold
     */
    Double getUnstructuredMlFraudThreshold();
    
    /**
     * Get unstructured ML suspicious threshold
     */
    Double getUnstructuredMlSuspiciousThreshold();
    
    /**
     * Get risk score fraud threshold
     */
    Double getRiskScoreFraudThreshold();
    
    /**
     * Get risk score suspicious threshold
     */
    Double getRiskScoreSuspiciousThreshold();
    
    /**
     * Get risk rules configuration
     */
    List<Map<String, Object>> getRiskRules();
}