package com.primebank.fraud;

import java.math.BigDecimal;

public class ThresholdValue {

    private String ruleKey;
    private BigDecimal ruleValue;

    public ThresholdValue(String ruleKey, BigDecimal ruleValue) {
        this.ruleKey = ruleKey;
        this.ruleValue = ruleValue;
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public BigDecimal getRuleValue() {
        return ruleValue;
    }
}
