package com.primebank.fraud;

import java.util.List;
import java.util.Map;

public interface CardMccRuleService {

    // Existing rule
    List<String> getSuspiciousMccList();

    // NEW rule (Product + MCC)
    Map<String, String> getProductMccRiskMap(String productCode);
}
