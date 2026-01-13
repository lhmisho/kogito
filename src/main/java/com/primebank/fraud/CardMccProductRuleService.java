package com.primebank.fraud;

import java.util.Map;

public interface CardMccProductRuleService {
    Map<String, String> getRiskByProductAndMcc(String productCode);
}
