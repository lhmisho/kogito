package com.primebank.fraud;

import java.util.List;

public interface CardFraudThresholdService {

    /**
     * Load all active card fraud thresholds into memory.
     * Usually called once per request or during startup.
     */
    void loadActiveThresholds();

    /**
     * Fetch a numeric threshold by key.
     * Example keys:
     *  - VELOCITY_5_COUNT
     *  - VELOCITY_5_AMOUNT
     *  - ML_FRAUD_THRESHOLD
     */
    Number get(String thresholdKey);

    /**
     * Returns MCC group IDs marked as SUSPICIOUS.
     * This is injected directly into DMN as a FEEL list.
     */
    List<String> getSuspiciousMccList();
}
