package com.primebank.fraud;

import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Path("/myprime")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class MyPrimeFraudOrchestratorResource {

    private static final String DMN_NAMESPACE = "https://primebank.com/dmn/myprime";
    private static final String DMN_MODEL_NAME = "MyPrimeFraudDecision";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Inject
    DecisionModels decisionModels;

    @Inject
    MyPrimeFraudThresholdService thresholdService;

    @POST
    @Path("/fraud/decision")
    public Response evaluateFraudDecision(Map<String, Object> request) {
        try {
            // 1. Load thresholds
            thresholdService.loadActiveThresholds();
            
            // 2. Build DMN input
            Map<String, Object> dmnInput = buildDmnInput(request);
            
            // 3. Execute DMN
            Map<String, Object> dmnResult = executeDmn(dmnInput);
            
            // 4. Build final response
            Map<String, Object> response = buildResponse(request, dmnResult);
            
            // 5. Optional: Log audit trail
            logAuditTrail(request, response);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of(
                    "error", "MyPrime fraud decision failed",
                    "message", e.getMessage(),
                    "timestamp", Instant.now().toString(),
                    "transaction_id", request.get("transaction_id")
                ))
                .build();
        }
    }

    private Map<String, Object> buildDmnInput(Map<String, Object> request) {
        Map<String, Object> dmnInput = new HashMap<>();
        
        // 1. Add all transaction data with proper type conversion
        addTransactionData(dmnInput, request);
        
        // 2. Add ML scores
        addMlScores(dmnInput, request);
        
        // 3. Add all thresholds from database
        addThresholds(dmnInput);
        
        // 4. Add calculated fields
        addCalculatedFields(dmnInput, request);
        
        return dmnInput;
    }

    private void addTransactionData(Map<String, Object> dmnInput, Map<String, Object> request) {
        // Transaction Details
        dmnInput.put("transaction_id", asString(request.get("transaction_id")));
        dmnInput.put("transaction_amount", asNumber(request.get("transaction_amount"), 0.0));
        dmnInput.put("transaction_type", asString(request.get("transaction_type")));
        dmnInput.put("transaction_timestamp", asString(request.get("transaction_timestamp")));
        
        // Login & Device Details
        dmnInput.put("is_new_device", asBoolean(request.get("is_new_device"), false));
        dmnInput.put("failed_logins_last_1hr", asNumber(request.get("failed_logins_last_1hr"), 0));
        dmnInput.put("device_change_count_30d", asNumber(request.get("device_change_count_30d"), 0));
        dmnInput.put("login_count_last_24hr", asNumber(request.get("login_count_last_24hr"), 0));
        dmnInput.put("days_since_last_device_change", asNumber(request.get("days_since_last_device_change"), 999));
        
        // Beneficiary Details
        dmnInput.put("is_new_beneficiary", asBoolean(request.get("is_new_beneficiary"), false));
        dmnInput.put("beneficiaries_added_last_24hr", asNumber(request.get("beneficiaries_added_last_24hr"), 0));
        dmnInput.put("beneficiaries_deleted_last_24hr", asNumber(request.get("beneficiaries_deleted_last_24hr"), 0));
        dmnInput.put("beneficiaries_added_last_7days", asNumber(request.get("beneficiaries_added_last_7days"), 0));
        dmnInput.put("beneficiaries_deleted_last_7days", asNumber(request.get("beneficiaries_deleted_last_7days"), 0));
        
        // Transaction Velocity
        dmnInput.put("transaction_count_last_5_min", asNumber(request.get("transaction_count_last_5_min"), 0));
        dmnInput.put("transaction_count_last_30_min", asNumber(request.get("transaction_count_last_30_min"), 0));
        dmnInput.put("amount_sum_last_5_min", asNumber(request.get("amount_sum_last_5_min"), 0));
        dmnInput.put("amount_sum_last_30_min", asNumber(request.get("amount_sum_last_30_min"), 0));
        
        // OTP & Authentication
        dmnInput.put("otp_required", asNumber(request.get("otp_required"), 0));
        dmnInput.put("otp_verified", asNumber(request.get("otp_verified"), 0));
        
        // MFS Details
        dmnInput.put("is_mfs_debit", asNumber(request.get("is_mfs_debit"), 0));
        
        // Amount Patterns
        dmnInput.put("avg_transaction_amount_30d", asNumber(request.get("avg_transaction_amount_30d"), 0));
        dmnInput.put("max_transaction_amount_30d", asNumber(request.get("max_transaction_amount_30d"), 0));
        dmnInput.put("is_round_amount", asBoolean(request.get("is_round_amount"), false));
        dmnInput.put("applicable_daily_limit", asNumber(request.get("applicable_daily_limit"), 0));
        
        // Account Details
        dmnInput.put("account_age_days", asNumber(request.get("account_age_days"), 0));
        dmnInput.put("avg_daily_transaction_count", asNumber(request.get("avg_daily_transaction_count"), 0));
        dmnInput.put("days_since_last_transaction", asNumber(request.get("days_since_last_transaction"), 0));
        
        // Fund Movement
        dmnInput.put("credit_amount_last_1hr", asNumber(request.get("credit_amount_last_1hr"), 0));
        dmnInput.put("debit_amount_last_1hr", asNumber(request.get("debit_amount_last_1hr"), 0));
    }

    private void addMlScores(Map<String, Object> dmnInput, Map<String, Object> request) {
        // XGBoost ML Score
        if (request.containsKey("xgboost_ml_score")) {
            dmnInput.put("xgboost_ml_score", asNumber(request.get("xgboost_ml_score"), 0.0));
        } else if (request.containsKey("ml_score")) {
            // Backward compatibility
            dmnInput.put("xgboost_ml_score", asNumber(request.get("ml_score"), 0.0));
        } else {
            dmnInput.put("xgboost_ml_score", 0.0);
        }
        
        // Unstructured ML Score
        if (request.containsKey("unstructured_ml_score")) {
            dmnInput.put("unstructured_ml_score", asNumber(request.get("unstructured_ml_score"), 0.0));
        } else {
            dmnInput.put("unstructured_ml_score", 0.0);
        }
    }

    private void addThresholds(Map<String, Object> dmnInput) {
        Map<String, Number> thresholds = thresholdService.getAllThresholds();
        
        // Add all thresholds to DMN input
        thresholds.forEach((key, value) -> {
            String dmnKey = key;
            // Handle special cases where DMN expects different key names
            if ("FAILED_LOGINS_1HR".equals(key)) {
                dmnKey = "FAILED_LOGINS_THRESHOLD";
            } else if ("DEVICE_CHANGE_30D".equals(key)) {
                dmnKey = "DEVICE_CHANGE_THRESHOLD";
            } else if ("LOGIN_COUNT_24HR".equals(key)) {
                dmnKey = "LOGIN_COUNT_THRESHOLD";
            } else if ("BENEFICIARY_ADD_24HR".equals(key)) {
                dmnKey = "BENEFICIARY_ADD_24HR_THRESHOLD";
            } else if ("BENEFICIARY_ADD_7D".equals(key)) {
                dmnKey = "BENEFICIARY_ADD_7D_THRESHOLD";
            } else if ("MFS_TRANSACTION_30MIN".equals(key)) {
                dmnKey = "MFS_TRANSACTION_30MIN_THRESHOLD";
            } else if ("MFS_VELOCITY_5MIN".equals(key)) {
                dmnKey = "MFS_VELOCITY_5MIN_THRESHOLD";
            } else if ("ROUND_AMOUNT_THRESHOLD".equals(key)) {
                dmnKey = "ROUND_AMOUNT_THRESHOLD";
            } else if ("LIMIT_PERCENTAGE_THRESHOLD".equals(key)) {
                dmnKey = "LIMIT_PERCENTAGE_THRESHOLD";
            } else if ("DAILY_LIMIT_PERCENTAGE".equals(key)) {
                dmnKey = "DAILY_LIMIT_PERCENTAGE_THRESHOLD";
            } else if ("VELOCITY_5MIN_COUNT".equals(key)) {
                dmnKey = "VELOCITY_5MIN_COUNT_THRESHOLD";
            } else if ("VELOCITY_30MIN_COUNT".equals(key)) {
                dmnKey = "VELOCITY_30MIN_COUNT_THRESHOLD";
            } else if ("RAPID_AMOUNT_5MIN_PERCENT".equals(key)) {
                dmnKey = "RAPID_AMOUNT_5MIN_PERCENT_THRESHOLD";
            }
            
            dmnInput.put(dmnKey, value.doubleValue());
        });
        
        // Add risk score thresholds
        dmnInput.put("RISK_SCORE_FRAUD_THRESHOLD", thresholdService.getRiskScoreFraudThreshold());
        dmnInput.put("RISK_SCORE_SUSPICIOUS_THRESHOLD", thresholdService.getRiskScoreSuspiciousThreshold());
    }

    private void addCalculatedFields(Map<String, Object> dmnInput, Map<String, Object> request) {
        // Calculate hour from timestamp for RULE 4
        String timestamp = asString(request.get("transaction_timestamp"));
        if (timestamp != null) {
            try {
                LocalDateTime dt = LocalDateTime.parse(timestamp, TIMESTAMP_FORMATTER);
                dmnInput.put("login_hour", dt.getHour());
            } catch (Exception e) {
                dmnInput.put("login_hour", 12); // Default to noon if parsing fails
            }
        } else {
            dmnInput.put("login_hour", 12);
        }
    }

    private Map<String, Object> executeDmn(Map<String, Object> dmnInput) {
        DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
        
        if (model == null) {
            throw new IllegalStateException("MyPrime DMN model not found: " + DMN_MODEL_NAME);
        }
        
        return model.evaluateAll(model.newContext(dmnInput)).getContext().getAll();
    }

    private Map<String, Object> buildResponse(Map<String, Object> request, Map<String, Object> dmnResult) {
        @SuppressWarnings("unchecked")
        Map<String, Object> finalDecision = (Map<String, Object>) dmnResult.get("FinalFraudDecision");
        
        if (finalDecision == null) {
            throw new IllegalStateException("DMN returned no final decision");
        }
        
        // Extract rule results for detailed reporting
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ruleResults = (List<Map<String, Object>>) dmnResult.get("MyPrimeFraudDecision");
        
        Map<String, Object> response = new HashMap<>();
        response.put("transaction_id", request.get("transaction_id"));
        response.put("fraud_decision", finalDecision.get("fraud_decision"));
        response.put("risk_score", finalDecision.get("risk_score"));
        response.put("fraud_reasons", finalDecision.get("fraud_reasons"));
        response.put("recommended_action", finalDecision.get("recommended_action"));
        response.put("max_severity", finalDecision.get("max_severity"));
        response.put("triggered_rules_count", finalDecision.get("triggered_rules_count"));
        response.put("evaluated_at", Instant.now().toString());
        response.put("success", true);
        
        // Add detailed rule information if available
        if (ruleResults != null && !ruleResults.isEmpty()) {
            response.put("triggered_rules", getTriggeredRulesDetails(ruleResults));
            response.put("total_risk_contribution", calculateTotalRisk(ruleResults));
        }
        
        // Add ML scores
        response.put("xgboost_ml_score", request.getOrDefault("xgboost_ml_score", 0.0));
        response.put("unstructured_ml_score", request.getOrDefault("unstructured_ml_score", 0.0));
        
        return response;
    }

    private List<Map<String, Object>> getTriggeredRulesDetails(List<Map<String, Object>> ruleResults) {
        return ruleResults.stream()
            .filter(rule -> ((Number) rule.get("risk_score_contribution")).doubleValue() > 0)
            .map(rule -> {
                Map<String, Object> detail = new HashMap<>();
                detail.put("fraud_reason", rule.get("fraud_reason"));
                detail.put("risk_score_contribution", rule.get("risk_score_contribution"));
                detail.put("severity", rule.get("severity"));
                return detail;
            })
            .collect(Collectors.toList());
    }

    private double calculateTotalRisk(List<Map<String, Object>> ruleResults) {
        return ruleResults.stream()
            .mapToDouble(rule -> ((Number) rule.get("risk_score_contribution")).doubleValue())
            .sum();
    }

    private void logAuditTrail(Map<String, Object> request, Map<String, Object> response) {
        // This would typically write to a database or log file
        String logEntry = String.format(
            "MYPRIME_FRAUD_DECISION|%s|%s|%s|%.2f|%s",
            Instant.now().toString(),
            request.get("transaction_id"),
            response.get("fraud_decision"),
            response.get("risk_score"),
            response.get("recommended_action")
        );
        
        System.out.println(logEntry);
    }

    // Helper methods
    private static String asString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return defaultValue;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    private static Double asNumber(Object v, double defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // Health check endpoint
    @GET
    @Path("/health")
    public Response healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "MyPrimeFraudDetection");
        health.put("timestamp", Instant.now().toString());
        
        try {
            DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            health.put("dmn_model_loaded", model != null);
            health.put("dmn_model_name", DMN_MODEL_NAME);
        } catch (Exception e) {
            health.put("dmn_model_loaded", false);
            health.put("dmn_error", e.getMessage());
        }
        
        if (thresholdService instanceof MyPrimeFraudThresholdServiceImpl) {
            MyPrimeFraudThresholdServiceImpl service = (MyPrimeFraudThresholdServiceImpl) thresholdService;
            health.put("cache_stats", service.getCacheStats());
        }
        
        return Response.ok(health).build();
    }

    // Test endpoint
    @POST
    @Path("/test")
    public Response testDecision(@QueryParam("scenario") String scenario) {
        Map<String, Object> testRequest = createTestScenario(scenario);
        return evaluateFraudDecision(testRequest);
    }

    private Map<String, Object> createTestScenario(String scenario) {
        Map<String, Object> request = new HashMap<>();
        request.put("transaction_id", "TEST_" + scenario + "_" + System.currentTimeMillis());
        request.put("transaction_timestamp", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        
        switch (scenario) {
            case "high_risk":
                request.put("is_new_device", true);
                request.put("is_new_beneficiary", true);
                request.put("transaction_amount", 200000.0);
                request.put("avg_transaction_amount_30d", 50000.0);
                request.put("transaction_count_last_5_min", 5);
                request.put("xgboost_ml_score", 0.85);
                break;
                
            case "medium_risk":
                request.put("is_new_device", true);
                request.put("transaction_amount", 75000.0);
                request.put("failed_logins_last_1hr", 2);
                request.put("xgboost_ml_score", 0.65);
                break;
                
            case "low_risk":
                request.put("transaction_amount", 10000.0);
                request.put("is_new_device", false);
                request.put("is_new_beneficiary", false);
                request.put("xgboost_ml_score", 0.2);
                break;
                
            default:
                // Normal transaction
                request.put("transaction_amount", 15000.0);
                request.put("transaction_type", "NAGAD");
                request.put("is_mfs_debit", 2);
                request.put("xgboost_ml_score", 0.1);
                break;
        }
        
        return request;
    }
}