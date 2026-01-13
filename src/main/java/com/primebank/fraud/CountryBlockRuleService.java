package com.primebank.fraud;

public interface CountryBlockRuleService {

    /**
     * @return FRAUD | SUSPICIOUS | null
     */
    String getCountryDecision(String countryCode);
}
