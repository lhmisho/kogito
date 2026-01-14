package com.primebank.fraud;

import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Path("/myprime-simple")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class MyPrimeSimpleTestResource {

    private static final String DMN_NAMESPACE = "https://primebank.com/dmn/myprime";
    private static final String DMN_MODEL_NAME = "MyPrimeSimpleDecision";

    @Inject
    DecisionModels decisionModels;

    @GET
    @Path("/health")
    public Response health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "MyPrimeSimpleTest");
        response.put("dmn_model", DMN_MODEL_NAME);
        
        try {
            DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            response.put("dmn_loaded", model != null);
            response.put("success", true);
        } catch (Exception e) {
            response.put("dmn_loaded", false);
            response.put("error", e.getMessage());
            response.put("success", false);
        }
        
        return Response.ok(response).build();
    }

    @POST
    @Path("/decision")
    public Response evaluateDecision(Map<String, Object> request) {
        try {
            System.out.println("Received request: " + request);
            
            // Build DMN input
            Map<String, Object> dmnInput = new HashMap<>();
            dmnInput.put("transaction_amount", asNumber(request.get("transaction_amount"), 0.0));
            dmnInput.put("is_new_device", asBoolean(request.get("is_new_device"), false));
            dmnInput.put("is_new_beneficiary", asBoolean(request.get("is_new_beneficiary"), false));
            dmnInput.put("failed_logins_last_1hr", asNumber(request.get("failed_logins_last_1hr"), 0));
            dmnInput.put("xgboost_ml_score", asNumber(request.get("xgboost_ml_score"), 0.0));
            
            System.out.println("DMN Input: " + dmnInput);
            
            // Get DMN model
            DecisionModel model = decisionModels.getDecisionModel(DMN_NAMESPACE, DMN_MODEL_NAME);
            if (model == null) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                        "error", "DMN model not found",
                        "model", DMN_MODEL_NAME,
                        "namespace", DMN_NAMESPACE
                    ))
                    .build();
            }
            
            // Execute DMN
            Map<String, Object> dmnResult = model.evaluateAll(model.newContext(dmnInput))
                .getContext()
                .getAll();
            
            System.out.println("DMN Result: " + dmnResult);
            
            // Extract decision
            @SuppressWarnings("unchecked")
            Map<String, Object> decision = (Map<String, Object>) dmnResult.get(DMN_MODEL_NAME);
            
            Map<String, Object> response = new HashMap<>();
            response.put("transaction_id", request.get("transaction_id"));
            response.put("fraud_decision", decision.get("fraud_decision"));
            response.put("fraud_reason", decision.get("fraud_reason"));
            response.put("success", true);
            response.put("dmn_input", dmnInput);
            
            return Response.ok(response).build();
            
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of(
                    "error", "Decision evaluation failed",
                    "message", e.getMessage(),
                    "success", false
                ))
                .build();
        }
    }

    @GET
    @Path("/test")
    public Response test() {
        Map<String, Object> testRequest = new HashMap<>();
        testRequest.put("transaction_id", "TEST_001");
        testRequest.put("transaction_amount", 150000.0);
        testRequest.put("is_new_device", true);
        testRequest.put("is_new_beneficiary", true);
        testRequest.put("failed_logins_last_1hr", 0);
        testRequest.put("xgboost_ml_score", 0.65);
        
        return evaluateDecision(testRequest);
    }

    private static Double asNumber(Object v, double defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static Boolean asBoolean(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }
}