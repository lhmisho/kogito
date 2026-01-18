package com.primebank.fraud;

import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/myprime")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MyPrimeResource {

    // THESE MUST MATCH THE DMN FILE EXACTLY!
    private static final String DMN_NAMESPACE = "https://primebank.com/dmn/myprime";
    private static final String DMN_MODEL_NAME = "MyPrimeDecision";  // Must match <decision name="MyPrimeDecision">

    @Inject
    DecisionModels decisionModels;

    @GET
    @Path("/health")
    public Response health() {
        try {
            DecisionModel decisionModel = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            
            Map<String, Object> response = new HashMap<>();
            if (decisionModel != null) {
                response.put("service", "MyPrimeDecision");
                response.put("dmn_model", DMN_MODEL_NAME);
                response.put("dmn_loaded", true);
                response.put("success", true);
                response.put("status", "UP");
                return Response.ok(response).build();
            } else {
                response.put("success", false);
                response.put("error", "DMN model not found");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(response).build();
            }
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getClass().getSimpleName());
            response.put("message", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(response).build();
        }
    }

    private static final Map<String, String> MYPRIME_FRAUD_REASON_LABELS = Map.ofEntries(

        // ===== FRAUD =====
        Map.entry("HIGH_ML_SCORE", "High Fraud Risk Score"),
        Map.entry("VERY_LARGE_AMOUNT", "Very Large Transaction Amount"),
        Map.entry("EXTREME_FAILED_LOGINS", "Excessive Failed Login Attempts"),
        Map.entry("HIGH_VELOCITY_24HR_COUNT", "High Transaction Count in 24 Hours"),
        Map.entry("HIGH_VELOCITY_24HR_AMOUNT", "High Transaction Amount in 24 Hours"),
        Map.entry("NEW_DEVICE_AND_BENEFICIARY", "New Device and New Beneficiary"),
        Map.entry("EXCESSIVE_NEW_DEVICES", "Excessive New Devices Detected"),
        Map.entry("EXCESSIVE_NEW_BENEFICIARIES", "Excessive New Beneficiaries Detected"),

        // ===== SUSPICIOUS =====
        Map.entry("LARGE_AMOUNT", "Large Transaction Amount"),
        Map.entry("MULTIPLE_FAILED_LOGINS", "Multiple Failed Login Attempts"),
        Map.entry("HIGH_VELOCITY_1HR_COUNT", "High Transaction Count in 1 Hour"),
        Map.entry("HIGH_VELOCITY_1HR_AMOUNT", "High Transaction Amount in 1 Hour"),
        Map.entry("MULTI_COUNTRY_ACTIVITY", "Transactions from Multiple Countries"),
        Map.entry("MULTI_CITY_ACTIVITY", "Transactions from Multiple Cities"),
        Map.entry("ODD_HOURS_LARGE_TXN", "Large Transaction During Odd Hours"),
        Map.entry("ODD_HOURS_NEW_DEVICE", "New Device Used During Odd Hours"),
        Map.entry("SUSPICIOUS_MCC_CODE", "Suspicious Merchant Category Code"),
        Map.entry("NEW_DEVICE", "Transaction from New Device"),
        Map.entry("NEW_BENEFICIARY", "Transaction to New Beneficiary"),
        Map.entry("MODERATE_ML_SCORE", "Moderate Fraud Risk Score"),

        // ===== DEFAULT =====
        Map.entry("NONE", "No Risk Detected")
    );

    @Inject
    MyPrimeThresholdService thresholdService;

    @POST
    @Path("/decision")
    public Response evaluateDecision(Map<String, Object> transaction) {
        try {
            System.out.println("MyPrime Decision - Input: " + transaction);
            
            // Get DMN model
            DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            
            if (model == null) {
                throw new IllegalStateException("DMN model not found: " + DMN_MODEL_NAME);
            }
            
            // Prepare DMN input with defaults and thresholds
            Map<String, Object> dmnInput = new HashMap<>();
            
            // Transaction data with defaults
            dmnInput.put("xgboost_ml_score", transaction.getOrDefault("xgboost_ml_score", 0.0));
            dmnInput.put("transaction_amount", transaction.getOrDefault("transaction_amount", 0.0));
            dmnInput.put("is_new_device", transaction.getOrDefault("is_new_device", false));
            dmnInput.put("is_new_beneficiary", transaction.getOrDefault("is_new_beneficiary", false));
            dmnInput.put("failed_logins_last_1hr", transaction.getOrDefault("failed_logins_last_1hr", 0.0));
            dmnInput.put("txn_count_1hr", transaction.getOrDefault("txn_count_1hr", 0.0));
            dmnInput.put("txn_amount_1hr", transaction.getOrDefault("txn_amount_1hr", 0.0));
            dmnInput.put("txn_count_24hr", transaction.getOrDefault("txn_count_24hr", 0.0));
            dmnInput.put("txn_amount_24hr", transaction.getOrDefault("txn_amount_24hr", 0.0));
            dmnInput.put("country_count_24hr", transaction.getOrDefault("country_count_24hr", 0.0));
            dmnInput.put("city_count_24hr", transaction.getOrDefault("city_count_24hr", 0.0));
            dmnInput.put("is_odd_hours", transaction.getOrDefault("is_odd_hours", false));
            dmnInput.put("mcc_code", transaction.getOrDefault("mcc_code", ""));
            dmnInput.put("new_devices_7days", transaction.getOrDefault("new_devices_7days", 0.0));
            dmnInput.put("new_beneficiaries_7days", transaction.getOrDefault("new_beneficiaries_7days", 0.0));
            
            // Add all thresholds
            Map<String, Object> allThresholds = thresholdService.getAllThresholds();
            dmnInput.putAll(allThresholds);
            
            System.out.println("DMN Input (with thresholds): " + dmnInput.keySet());
            
            // Evaluate DMN
            Map<String, Object> dmnResult = model.evaluateAll(model.newContext(dmnInput)).getContext().getAll();
            
            // Extract decision
            Object decisionObj = dmnResult.get(DMN_MODEL_NAME);
            
            if (decisionObj == null) {
                System.out.println("Available keys in DMN result: " + dmnResult.keySet());
                throw new IllegalStateException("DMN returned no decision under key: " + DMN_MODEL_NAME);
            }
            
            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("transaction_id", transaction.get("transaction_id"));
            response.put("evaluated_at", Instant.now().toString());
            
            // Extract decision results
            if (decisionObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> decision = (Map<String, Object>) decisionObj;
                response.put("fraud_decision", decision.get("fraud_decision"));
                response.put("fraud_reason", MYPRIME_FRAUD_REASON_LABELS.getOrDefault(decision.get("fraud_reason"), decision.get("fraud_reason").toString()));
            }
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            System.err.println("MyPrime Decision Error: " + e.getMessage());
            e.printStackTrace();
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "MyPrime decision failed");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", Instant.now().toString());
            
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(errorResponse)
                    .build();
        }
    }
    @GET
    @Path("/run-all-tests")
    public Response runAllTests() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> tests = new ArrayList<>();
        
        // Test 1: High ML Score
        tests.add(runSingleTest("High ML Score", Map.of(
            "xgboost_ml_score", 0.75
        )));
        
        // Test 2: New Device + Beneficiary
        tests.add(runSingleTest("New Device + Beneficiary", Map.of(
            "is_new_device", true,
            "is_new_beneficiary", true
        )));
        
        // Test 3: Multiple Failed Logins
        tests.add(runSingleTest("Multiple Failed Logins", Map.of(
            "failed_logins_last_1hr", 5.0
        )));
        
        // Test 4: Large Amount
        tests.add(runSingleTest("Large Amount", Map.of(
            "transaction_amount", 150000.0
        )));
        
        // Test 5: Normal Transaction
        tests.add(runSingleTest("Normal Transaction", Map.of(
            "xgboost_ml_score", 0.45,
            "transaction_amount", 50000.0
        )));
        
        result.put("success", true);
        result.put("total_tests", tests.size());
        result.put("tests", tests);
        
        return Response.ok(result).build();
    }

    private Map<String, Object> runSingleTest(String testName, Map<String, Object> input) {
        try {
            Map<String, Object> testInput = new HashMap<>(input);
            testInput.put("transaction_id", "TEST-" + testName.replace(" ", "-"));
            
            DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            Map<String, Object> dmnInput = new HashMap<>();
            
            // Add defaults
            dmnInput.put("xgboost_ml_score", testInput.getOrDefault("xgboost_ml_score", 0.0));
            dmnInput.put("transaction_amount", testInput.getOrDefault("transaction_amount", 0.0));
            dmnInput.put("is_new_device", testInput.getOrDefault("is_new_device", false));
            dmnInput.put("is_new_beneficiary", testInput.getOrDefault("is_new_beneficiary", false));
            dmnInput.put("failed_logins_last_1hr", testInput.getOrDefault("failed_logins_last_1hr", 0.0));
            
            Map<String, Object> dmnResult = model.evaluateAll(model.newContext(dmnInput)).getContext().getAll();
            Map<String, Object> decision = (Map<String, Object>) dmnResult.get(DMN_MODEL_NAME);
            
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("test_name", testName);
            testResult.put("input", testInput);
            testResult.put("result", decision);
            testResult.put("passed", true);
            
            return testResult;
        } catch (Exception e) {
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("test_name", testName);
            testResult.put("error", e.getMessage());
            testResult.put("passed", false);
            return testResult;
        }
    }

    @GET
    @Path("/thresholds")
    public Response getThresholds() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("thresholds", thresholdService.getAllThresholds());
        response.put("count", thresholdService.getAllThresholds().size());
        return Response.ok(response).build();
    }

    @POST
    @Path("/thresholds/reload")
    public Response reloadThresholds() {
        try {
            thresholdService.reloadThresholds();
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Thresholds reloaded successfully");
            response.put("threshold_count", thresholdService.getAllThresholds().size());
            return Response.ok(response).build();
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Failed to reload thresholds");
            errorResponse.put("message", e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(errorResponse).build();
        }
    }

    @GET
    @Path("/verify-all-rules")
    public Response verifyAllRules() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> ruleTests = new ArrayList<>();
        
        // Get current thresholds
        Map<String, Object> thresholds = thresholdService.getAllThresholds();
        double mlFraudThreshold = thresholdService.getAsDouble("ML_FRAUD_THRESHOLD");
        double largeAmountThreshold = thresholdService.getAsDouble("LARGE_AMOUNT_THRESHOLD");
        double veryLargeAmountThreshold = thresholdService.getAsDouble("VERY_LARGE_AMOUNT_THRESHOLD");
        int failedLoginsFraudThreshold = thresholdService.getAsInteger("FAILED_LOGINS_FRAUD_THRESHOLD");
        int failedLoginsSuspiciousThreshold = thresholdService.getAsInteger("FAILED_LOGINS_SUSPICIOUS_THRESHOLD");
        
        // Test each rule
        ruleTests.add(testRule("Rule 1: High ML Score", 
            Map.of("xgboost_ml_score", mlFraudThreshold + 0.1), "FRAUD", "HIGH_ML_SCORE"));
        
        ruleTests.add(testRule("Rule 2: Very Large Amount", 
            Map.of("transaction_amount", veryLargeAmountThreshold + 1000), "FRAUD", "VERY_LARGE_AMOUNT"));
        
        ruleTests.add(testRule("Rule 3: Extreme Failed Logins", 
            Map.of("failed_logins_last_1hr", failedLoginsFraudThreshold + 5.0), "FRAUD", "EXTREME_FAILED_LOGINS"));
        
        ruleTests.add(testRule("Rule 4: High 24hr Transaction Count", 
            Map.of("txn_count_24hr", 25.0), "FRAUD", "HIGH_VELOCITY_24HR_COUNT"));
        
        ruleTests.add(testRule("Rule 5: High 24hr Transaction Amount", 
            Map.of("txn_amount_24hr", 600000.0), "FRAUD", "HIGH_VELOCITY_24HR_AMOUNT"));
        
        ruleTests.add(testRule("Rule 6: New Device + New Beneficiary", 
            Map.of("is_new_device", true, "is_new_beneficiary", true), "FRAUD", "NEW_DEVICE_AND_BENEFICIARY"));
        
        ruleTests.add(testRule("Rule 7: Excessive New Devices", 
            Map.of("new_devices_7days", 3.0), "FRAUD", "EXCESSIVE_NEW_DEVICES"));
        
        ruleTests.add(testRule("Rule 8: Excessive New Beneficiaries", 
            Map.of("new_beneficiaries_7days", 4.0), "FRAUD", "EXCESSIVE_NEW_BENEFICIARIES"));
        
        ruleTests.add(testRule("Rule 9: Large Amount", 
            Map.of("transaction_amount", largeAmountThreshold + 1000), "SUSPICIOUS", "LARGE_AMOUNT"));
        
        ruleTests.add(testRule("Rule 10: Multiple Failed Logins", 
            Map.of("failed_logins_last_1hr", failedLoginsSuspiciousThreshold + 2.0), "SUSPICIOUS", "MULTIPLE_FAILED_LOGINS"));
        
        ruleTests.add(testRule("Rule 11: High 1hr Transaction Count", 
            Map.of("txn_count_1hr", 6.0), "SUSPICIOUS", "HIGH_VELOCITY_1HR_COUNT"));
        
        ruleTests.add(testRule("Rule 12: High 1hr Transaction Amount", 
            Map.of("txn_amount_1hr", 110000.0), "SUSPICIOUS", "HIGH_VELOCITY_1HR_AMOUNT"));
        
        ruleTests.add(testRule("Rule 13: Multi-Country Activity", 
            Map.of("country_count_24hr", 4.0), "SUSPICIOUS", "MULTI_COUNTRY_ACTIVITY"));
        
        ruleTests.add(testRule("Rule 14: Multi-City Activity", 
            Map.of("city_count_24hr", 6.0), "SUSPICIOUS", "MULTI_CITY_ACTIVITY"));
        
        ruleTests.add(testRule("Rule 15: Odd Hours + Large Amount", 
            Map.of("is_odd_hours", true, "transaction_amount", 60000.0), "SUSPICIOUS", "ODD_HOURS_LARGE_TXN"));
        
        ruleTests.add(testRule("Rule 16: Odd Hours + New Device", 
            Map.of("is_odd_hours", true, "is_new_device", true), "SUSPICIOUS", "ODD_HOURS_NEW_DEVICE"));
        
        ruleTests.add(testRule("Rule 17: New Device", 
            Map.of("is_new_device", true), "SUSPICIOUS", "NEW_DEVICE"));
        
        ruleTests.add(testRule("Rule 18: New Beneficiary", 
            Map.of("is_new_beneficiary", true), "SUSPICIOUS", "NEW_BENEFICIARY"));
        
        ruleTests.add(testRule("Rule 19: Moderate ML Score", 
            Map.of("xgboost_ml_score", 0.55), "SUSPICIOUS", "MODERATE_ML_SCORE"));
        
        ruleTests.add(testRule("Rule 20: Normal Transaction", 
            Map.of("xgboost_ml_score", 0.3, "transaction_amount", 50000.0), "NORMAL", "NONE"));
        
        result.put("success", true);
        result.put("total_rules_tested", ruleTests.size());
        result.put("rules", ruleTests);
        result.put("thresholds_used", thresholds);
        
        return Response.ok(result).build();
    }

    private Map<String, Object> testRule(String ruleName, Map<String, Object> input, 
                                        String expectedDecision, String expectedReason) {
        try {
            DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            Map<String, Object> dmnInput = new HashMap<>(input);
            
            // Add all thresholds
            dmnInput.putAll(thresholdService.getAllThresholds());
            
            // Add defaults for required fields
            addDefaults(dmnInput);
            
            Map<String, Object> dmnResult = model.evaluateAll(model.newContext(dmnInput)).getContext().getAll();
            Map<String, Object> decision = (Map<String, Object>) dmnResult.get(DMN_MODEL_NAME);
            
            String actualDecision = (String) decision.get("fraud_decision");
            String actualReason = (String) decision.get("fraud_reason");
            
            boolean passed = expectedDecision.equals(actualDecision) && 
                            (expectedReason == null || expectedReason.equals(actualReason));
            
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("rule_name", ruleName);
            testResult.put("expected_decision", expectedDecision);
            testResult.put("actual_decision", actualDecision);
            testResult.put("expected_reason", expectedReason);
            testResult.put("actual_reason", actualReason);
            testResult.put("passed", passed);
            testResult.put("input", dmnInput);
            
            return testResult;
            
        } catch (Exception e) {
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("rule_name", ruleName);
            testResult.put("error", e.getMessage());
            testResult.put("passed", false);
            return testResult;
        }
    }

    private void addDefaults(Map<String, Object> input) {
        // Add default values for all required fields if not present
        String[] fields = {
            "xgboost_ml_score", "transaction_amount", "is_new_device", "is_new_beneficiary",
            "failed_logins_last_1hr", "txn_count_1hr", "txn_amount_1hr", "txn_count_24hr",
            "txn_amount_24hr", "country_count_24hr", "city_count_24hr", "is_odd_hours",
            "mcc_code", "new_devices_7days", "new_beneficiaries_7days"
        };
        
        for (String field : fields) {
            if (!input.containsKey(field)) {
                if (field.equals("is_new_device") || field.equals("is_new_beneficiary") || 
                    field.equals("is_odd_hours")) {
                    input.put(field, false);
                } else if (field.equals("mcc_code")) {
                    input.put(field, "");
                } else {
                    input.put(field, 0.0);
                }
            }
        }
    }

}