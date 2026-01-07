package com.primebank.fraud;

import org.kie.kogito.decision.DecisionModels;
import org.kie.kogito.decision.DecisionModel;
import org.kie.dmn.api.core.DMNContext;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Path("/fraud/decision")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class FraudDecisionOrchestratorResource {

    @Inject
    DecisionModels decisionModels;

    @Inject
    FraudThresholdService thresholdService;

//     @PostConstruct
//     void init() {
//         thresholdService.load();
//     }


    @POST
    public Map<String, Object> decide(Map<String, Object> txn) {

        thresholdService.load();

        /* ================= DMN #1 — RiskSignals ================= */
        Map<String, Object> riskSignalsInput = new HashMap<>();
        copy(txn, riskSignalsInput, "transaction_count_last_5_min");
        copy(txn, riskSignalsInput, "amount_sum_last_5_min");
        copy(txn, riskSignalsInput, "transaction_count_last_30_min");
        copy(txn, riskSignalsInput, "amount_sum_last_30_min");

        Map<String, Object> riskSignals =
                evaluate("RiskSignals", riskSignalsInput);

        Number velocityRiskScore =
                asNumber(riskSignals.get("VelocityRiskScore"));

        /* ================= DMN #2 — RiskScore ================= */
        Map<String, Object> riskScoreInput = new HashMap<>();
        riskScoreInput.put("VelocityRiskScore", velocityRiskScore);
        riskScoreInput.put("DeviceRiskScore", num(txn.get("DeviceRiskScore")));
        riskScoreInput.put("BeneficiaryRiskScore", num(txn.get("BeneficiaryRiskScore")));
        riskScoreInput.put("AuthRiskScore", num(txn.get("AuthRiskScore")));
        riskScoreInput.put("MlFraudScore", num(txn.get("MlFraudScore")));

        Map<String, Object> riskScore =
                evaluate("RiskScore", riskScoreInput);

        Number totalRiskScore =
                asNumber(riskScore.get("TotalRiskScore"));

        /* ================= DMN #3 — FraudDecision ================= */

        BigDecimal fraudThreshold =
                thresholdService.get("TOTAL_RISK_FRAUD");

        BigDecimal riskyThreshold =
                thresholdService.get("TOTAL_RISK_RISKY");

        BigDecimal suspiciousThreshold =
                thresholdService.get("TOTAL_RISK_SUSPICIOUS");

        Map<String, Object> fraudDecisionInput = new HashMap<>();
        fraudDecisionInput.put("TotalRiskScore", totalRiskScore);
        fraudDecisionInput.put("FRAUD_THRESHOLD", fraudThreshold);
        fraudDecisionInput.put("RISKY_THRESHOLD", riskyThreshold);
        fraudDecisionInput.put("SUSPICIOUS_THRESHOLD", suspiciousThreshold);

        Map<String, Object> fraudDecision =
                evaluate("FraudDecision", fraudDecisionInput);


        /* ================= DMN #4 — FraudAction ================= */
        Map<String, Object> fraudActionInput = new HashMap<>();
        fraudActionInput.put("VelocityRiskScore", velocityRiskScore);
        fraudActionInput.put("AuthRiskScore", num(txn.get("AuthRiskScore")));
        fraudActionInput.put("MerchantRiskScore", num(txn.get("MerchantRiskScore")));
        fraudActionInput.put("TerminalRiskScore", num(txn.get("TerminalRiskScore")));
        fraudActionInput.put("txn_channel", txn.get("txn_channel"));

        Map<String, Object> fraudAction =
                evaluate("FraudAction", fraudActionInput);

        /* ================= FINAL RESPONSE ================= */
        Map<String, Object> response = new HashMap<>();

        response.put("transactionId", txn.get("transaction_id"));

        response.put("finalDecision", Map.of(
                "fraudCategory", fraudDecision.get("FraudCategory"),
                "fraudAction", fraudAction.get("FraudDecision")
        ));

        response.put("scores", Map.of(
                "velocityRiskScore", velocityRiskScore,
                "deviceRiskScore", num(txn.get("DeviceRiskScore")),
                "beneficiaryRiskScore", num(txn.get("BeneficiaryRiskScore")),
                "authRiskScore", num(txn.get("AuthRiskScore")),
                "merchantRiskScore", num(txn.get("MerchantRiskScore")),
                "terminalRiskScore", num(txn.get("TerminalRiskScore")),
                "mlFraudScore", num(txn.get("MlFraudScore")),
                "totalRiskScore", totalRiskScore
        ));

        response.put("meta", Map.of(
                "evaluatedAt", Instant.now().toString(),
                "engine", "Kogito DMN",
                "mode", "RULES + ML"
        ));

        return response;
    }

    /* ================= Helper Methods ================= */
    private Map<String, Object> evaluate(String modelName, Map<String, Object> input) {

        DecisionModel model = decisionModels.getDecisionModel(
                "https://primebank.com/dmn/fraud",
                modelName
        );

        if (model == null) {
            throw new WebApplicationException("DMN not found: " + modelName, 500);
        }

        // ✅ CORRECT for your Kogito version
        return model
                .evaluateAll(
                    model.newContext(input)
                )
                .getContext()
                .getAll();
    }

    private static void copy(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key)) {
            dst.put(key, src.get(key));
        }
    }

    private static Number num(Object v) {
        if (v instanceof Number) return (Number) v;
        if (v instanceof String) return Double.parseDouble((String) v);
        return 0;
    }

    private static Number asNumber(Object v) {
        return v instanceof Number ? (Number) v : 0;
    }
}
