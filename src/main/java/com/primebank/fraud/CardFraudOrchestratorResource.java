package com.primebank.fraud;

import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/fraud/card/decision")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CardFraudOrchestratorResource {

    private static final String DMN_NAMESPACE = "https://primebank.com/dmn/card";
    private static final String DMN_MODEL_NAME = "CardFraudDecision";

    @Inject
    DecisionModels decisionModels;

    @Inject
    CardFraudThresholdService thresholdService;

    @Inject
    CardMccRuleService mccRuleService;

    @Inject
    CountryBlockRuleService countryBlockRuleService;

    @POST
    public Map<String, Object> decide(Map<String, Object> txn) {

        // 1) Load thresholds from DB/cache
        thresholdService.loadActiveThresholds();

        // 2) Prepare DMN input context
        Map<String, Object> dmnInput = new HashMap<>();

        // -----------------------------
        // A) Normalize key identifiers
        // -----------------------------
        String txnId = asString(txn.get("txn_id"));
        String productCode = asString(txn.get("product_code"));
        String mcc = asString(txn.get("mcc_group_id"));
        String txnCountry = asString(txn.get("txn_country"));

        // Keep these as explicit inputs (DMN expects them)
        dmnInput.put("product_code", productCode);
        dmnInput.put("mcc_group_id", mcc);
        dmnInput.put("txn_country", txnCountry);

        // -----------------------------
        // B) Scalar flags / channels
        // -----------------------------
        dmnInput.put("txn_channel", asString(txn.get("txn_channel")));

        dmnInput.put("is_magstripe", asBoolean(txn.get("is_magstripe"), false));
        dmnInput.put("is_3ds_authenticated", asBoolean(txn.get("is_3ds_authenticated"), true));

        // -----------------------------
        // C) Raw numeric counters (safe defaults)
        // -----------------------------
        dmnInput.put("txn_count_5", asNumber(txn.get("txn_count_5"), 0));
        dmnInput.put("txn_amount_5", asNumber(txn.get("txn_amount_5"), 0));
        dmnInput.put("txn_count_30", asNumber(txn.get("txn_count_30"), 0));
        dmnInput.put("txn_amount_30", asNumber(txn.get("txn_amount_30"), 0));

        dmnInput.put("wrong_cvv_10", asNumber(txn.get("wrong_cvv_10"), 0));
        dmnInput.put("wrong_pin_10", asNumber(txn.get("wrong_pin_10"), 0));

        dmnInput.put("card_failed_cnt1day", asNumber(txn.get("card_failed_cnt1day"), 0));
        dmnInput.put("ccy_cnt1hr", asNumber(txn.get("ccy_cnt1hr"), 0));

        dmnInput.put("card_terminal_txn_cnt1day", asNumber(txn.get("card_terminal_txn_cnt1day"), 0));
        dmnInput.put("card_terminal_txn_failed_cnt1day", asNumber(txn.get("card_terminal_txn_failed_cnt1day"), 0));

        dmnInput.put("ml_fraud_score_card", asNumber(txn.get("ml_fraud_score_card"), 0));

        // -----------------------------
        // D) DB-driven thresholds (must match DMN input names)
        // -----------------------------
        dmnInput.put("VELOCITY_5_COUNT", thresholdService.get("VELOCITY_5_COUNT"));
        dmnInput.put("VELOCITY_5_AMOUNT", thresholdService.get("VELOCITY_5_AMOUNT"));
        dmnInput.put("VELOCITY_30_COUNT", thresholdService.get("VELOCITY_30_COUNT"));
        dmnInput.put("VELOCITY_30_AMOUNT", thresholdService.get("VELOCITY_30_AMOUNT"));
        dmnInput.put("FAILED_TXN_1DAY", thresholdService.get("FAILED_TXN_1DAY"));
        dmnInput.put("ML_FRAUD_THRESHOLD", thresholdService.get("ML_FRAUD_THRESHOLD"));

        // -----------------------------
        // E) DB-driven lists
        // -----------------------------
        List<String> suspiciousMccList = mccRuleService.getSuspiciousMccList();
        dmnInput.put("SUSPICIOUS_MCC_LIST", suspiciousMccList);

        // -----------------------------
        // F) COUNTRY BLOCK (JAVA decides, DMN consumes scalar)
        // -----------------------------
        String countryDecision = countryBlockRuleService.getCountryDecision(txnCountry);
        dmnInput.put("COUNTRY_RISK", countryDecision);

        // -----------------------------
        // G) PRODUCT + MCC (JAVA decides, DMN consumes scalar)
        // -----------------------------
        String productMccRisk = null;
        if (productCode != null && !productCode.isBlank() && mcc != null && !mcc.isBlank()) {
            Map<String, String> productMccRiskMap = mccRuleService.getProductMccRiskMap(productCode);
            if (productMccRiskMap != null) {
                productMccRisk = productMccRiskMap.get(mcc);
            }
        }
        dmnInput.put("PRODUCT_MCC_RISK", productMccRisk);

        // -----------------------------
        // 3) Execute DMN
        // -----------------------------
        Map<String, Object> result = evaluate(DMN_MODEL_NAME, dmnInput);

        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) result.get(DMN_MODEL_NAME);

        if (decision == null) {
            // Provide context for faster debugging (no sensitive data)
            throw new WebApplicationException(
                    "DMN returned no decision result. Check DMN model name/namespace and required inputs. " +
                    "model=" + DMN_MODEL_NAME + ", namespace=" + DMN_NAMESPACE,
                    500
            );
        }

        // -----------------------------
        // 4) Response
        // -----------------------------
        Map<String, Object> response = new HashMap<>();
        response.put("transactionId", txnId);
        response.put("fraud_decision", decision.get("fraud_decision"));
        response.put("fraud_reason", decision.get("fraud_reason"));
        response.put("evaluatedAt", Instant.now().toString());

        return response;
    }

    private Map<String, Object> evaluate(String modelName, Map<String, Object> input) {
        DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, modelName);

        if (model == null) {
            throw new WebApplicationException("DMN not found: " + modelName + " (namespace=" + DMN_NAMESPACE + ")", 500);
        }

        return model.evaluateAll(model.newContext(input))
                .getContext()
                .getAll();
    }

    /* =========================
       Helpers: safe conversions
       ========================= */

    private static String asString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean asBoolean(Object v, boolean defaultVal) {
        if (v == null) return defaultVal;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return defaultVal;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    /**
     * DMN typeRef="number" accepts Java Number types well.
     * We normalize to Double (safe and predictable).
     */
    private static Double asNumber(Object v, double defaultVal) {
        if (v == null) return defaultVal;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
