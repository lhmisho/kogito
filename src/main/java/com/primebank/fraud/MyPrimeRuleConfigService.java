package com.primebank.fraud;

import java.util.Map;

public interface MyPrimeRuleConfigService {
    Map<String, Object> loadActiveRulesContext();
}
