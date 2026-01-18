package com.primebank.fraud;

import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Path("/CardFraudDecision")
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

    @POST
    public Response decide(Map<String, Object> txn) {
        try {
            // Build DMN input context
            Map<String, Object> dmnInput = buildDmnInput(txn);
            
            // Execute DMN
            Map<String, Object> dmnResult = evaluateDmn(dmnInput);
            
            // Build response
            Map<String, Object> response = buildResponse(txn, dmnResult);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            // Return error response
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Fraud decision failed");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", Instant.now().toString());
            
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponse)
                    .build();
        }
    }

    private Map<String, Object> buildDmnInput(Map<String, Object> txn) {
        Map<String, Object> input = new HashMap<>();

        // 1. Basic transaction data
        copyTransactionData(txn, input);
        
        // 2. Get all thresholds at once
        if (thresholdService instanceof CardFraudThresholdServiceImpl) {
            CardFraudThresholdServiceImpl service = (CardFraudThresholdServiceImpl) thresholdService;
            
            // Add all thresholds
            service.getAllThresholds().forEach(input::put);
            
            // Add MCC list
            input.put("SUSPICIOUS_MCC_LIST", service.getSuspiciousMccList());
            
            // Add country risk
            String countryCode = asString(txn.get("txn_country"));
            input.put("COUNTRY_RISK", service.getCountryRisk(countryCode));
            
            // Add product MCC risk
            String productCode = asString(txn.get("product_code"));
            String mccCode = asString(txn.get("mcc_group_id"));
            input.put("PRODUCT_MCC_RISK", service.getProductMccRisk(productCode, mccCode));
        } else {
            // Fallback to individual calls
            addIndividualThresholds(input);
        }
        
        return input;
    }

    private void copyTransactionData(Map<String, Object> source, Map<String, Object> target) {
        // Map all expected DMN inputs
        target.put("txn_channel", asString(source.get("txn_channel")));
        target.put("product_code", asString(source.get("product_code")));
        target.put("mcc_group_id", asString(source.get("mcc_group_id")));
        
        target.put("is_magstripe", asBoolean(source.get("is_magstripe"), false));
        target.put("is_3ds_authenticated", asBoolean(source.get("is_3ds_authenticated"), true));
        
        target.put("txn_count_5", asNumber(source.get("txn_count_5"), 0));
        target.put("txn_amount_5", asNumber(source.get("txn_amount_5"), 0));
        target.put("txn_count_30", asNumber(source.get("txn_count_30"), 0));
        target.put("txn_amount_30", asNumber(source.get("txn_amount_30"), 0));
        
        target.put("wrong_cvv_10", asNumber(source.get("wrong_cvv_10"), 0));
        target.put("wrong_pin_10", asNumber(source.get("wrong_pin_10"), 0));
        
        target.put("card_failed_cnt1day", asNumber(source.get("card_failed_cnt1day"), 0));
        target.put("ccy_cnt1hr", asNumber(source.get("ccy_cnt1hr"), 0));
        
        target.put("card_terminal_txn_cnt1day", asNumber(source.get("card_terminal_txn_cnt1day"), 0));
        target.put("card_terminal_txn_failed_cnt1day", asNumber(source.get("card_terminal_txn_failed_cnt1day"), 0));
        
        target.put("ml_fraud_score_card", asNumber(source.get("ml_fraud_score_card"), 0));
        
        // Additional fields for new rules
        target.put("processing_code", asString(source.get("processing_code")));
        target.put("merchant_name", asString(source.get("merchant_name")));
        target.put("txn_amount", asNumber(source.get("txn_amount"), 0));
        target.put("mcc_6011_txn_count_1hr", asNumber(source.get("mcc_6011_txn_count_1hr"), 0));

    }

    private void addIndividualThresholds(Map<String, Object> input) {
        input.put("VELOCITY_5_COUNT", thresholdService.get("VELOCITY_5_COUNT"));
        input.put("VELOCITY_5_AMOUNT", thresholdService.get("VELOCITY_5_AMOUNT"));
        input.put("VELOCITY_30_COUNT", thresholdService.get("VELOCITY_30_COUNT"));
        input.put("VELOCITY_30_AMOUNT", thresholdService.get("VELOCITY_30_AMOUNT"));
        input.put("FAILED_TXN_1DAY", thresholdService.get("FAILED_TXN_1DAY"));
        input.put("ML_FRAUD_THRESHOLD", thresholdService.get("ML_FRAUD_THRESHOLD"));
    }

    private Map<String, Object> evaluateDmn(Map<String, Object> input) {
        DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
        
        if (model == null) {
            throw new IllegalStateException("DMN model not found: " + DMN_MODEL_NAME);
        }
        
        return model.evaluateAll(model.newContext(input)).getContext().getAll();
    }

    private static final Map<String, String> FRAUD_REASON_LABELS = Map.ofEntries(
        Map.entry("COUNTRY_BLOCKED", "Transaction from Blocked Country"),
        Map.entry("COUNTRY_HIGH_RISK", "High Risk Country"),
        Map.entry("ML_FRAUD_SCORE_HIGH", "High Fraud Risk Score"),
        Map.entry("MAGSTRIPE_BLOCK", "Magstripe Transactions Blocked"),
        Map.entry("WRONG_CVV", "Multiple Wrong CVV Attempts"), // 1
        Map.entry("WRONG_PIN", "Multiple Wrong PIN Attempts"), // 1
        Map.entry("VELOCITY_5MIN", "High Transaction Velocity (5 Minutes)"), // 1
        Map.entry("VELOCITY_30MIN", "High Transaction Velocity (30 Minutes)"), // 1
        Map.entry("PRODUCT_MCC_RESTRICT", "Restricted Product and MCC Combination"),
        Map.entry("NON_3DS_TRANSACTION", "Non-3DS E-Commerce Transaction"),
        Map.entry("MULTI_CCY", "Multiple Currencies Used"), // Too many card present multi ccy in 1 hour
        Map.entry("MCC_FLAGGED", "High-Risk Merchant Category (MCC_FLAGGED)"),
        Map.entry("CARD_FAILED_ATTEMPTS_1DAY", "Too Many Failed Card Attempts"),
        Map.entry("TERMINAL_FAILURE_RATIO_HIGH", "Too many failed ratio terminal"), // 1
        Map.entry("RISKY_PROCESSING_CODE", "Risky Processing Code"),
        Map.entry("MERCHANT_NAME_PATTERN", "Suspicious Merchant Name"),
        Map.entry("MCC_6011_HIGH_FREQ_1HR", "High Frequency ATM Withdrawals"),
        Map.entry("NONE", "No Risk Detected")
    );

    private Map<String, Object> buildResponse(Map<String, Object> txn, Map<String, Object> dmnResult) {
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) dmnResult.get(DMN_MODEL_NAME);
        
        if (decision == null) {
            throw new IllegalStateException("DMN returned no decision");
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("transaction_id", txn.get("txn_id"));
        response.put("fraud_decision", decision.get("fraud_decision"));
        // response.put("fraud_reason", decision.get("fraud_reason"));
        response.put("fraud_reason", FRAUD_REASON_LABELS.getOrDefault(decision.get("fraud_reason"), decision.get("fraud_reason").toString()));
        response.put("evaluated_at", Instant.now().toString());
        response.put("model_version", "1.0");
        
        // Include the full DMN result if needed for debugging
        if (Boolean.TRUE.equals(txn.get("debug"))) {
            response.put("dmn_context", dmnResult);
        }
        
        return response;
    }

    // Helper methods
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