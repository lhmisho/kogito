package com.primebank.fraud;

import org.kie.kogito.decision.DecisionModel;
import org.kie.kogito.decision.DecisionModels;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.time.*;
import java.util.HashMap;
import java.util.Map;

@Path("/fraud/myprime/decision")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class MyPrimeFraudOrchestratorResource {

    private static final String DMN_NS = "https://primebank.com/dmn/myprime";
    private static final String DMN_MODEL = "MyPrimeFraudDecision";

    @Inject
    DecisionModels decisionModels;

    @Inject
    MyPrimeRuleConfigService ruleConfigService;

    @POST
    public Map<String, Object> decide(Map<String, Object> txn) {

        Map<String, Object> dmnInput = new HashMap<>();
        dmnInput.putAll(txn);

        // Derived (technical) facts
        dmnInput.put("login_hour_bd", computeLoginHourBd(txn.get("login_timestamp")));
        dmnInput.put("max_ml_score", maxNonNull(toDouble(txn.get("ml_fraud_score_myprime")),
                                               toDouble(txn.get("ml_fraud_score_myprime_unstructured_ml"))));

        // DB-driven rule params
        dmnInput.put("RULES", ruleConfigService.loadActiveRulesContext());

        // Evaluate DMN
        DecisionModel model = decisionModels.getDecisionModel(DMN_NS, DMN_MODEL);
        if (model == null) throw new WebApplicationException("DMN not found: " + DMN_MODEL, 500);

        Map<String, Object> result = model.evaluateAll(model.newContext(dmnInput)).getContext().getAll();
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) result.get(DMN_MODEL);

        if (decision == null) throw new WebApplicationException("DMN returned no decision", 500);

        // Add evaluatedAt (optional)
        decision.put("evaluatedAt", Instant.now().toString());
        return decision;
    }

    private static Integer computeLoginHourBd(Object loginTimestamp) {
        if (loginTimestamp == null) return null;
        try {
            // Expect ISO-8601 like "2026-01-12T11:00:00Z"
            Instant inst = Instant.parse(String.valueOf(loginTimestamp));
            return inst.atZone(ZoneId.of("Asia/Dhaka")).getHour();
        } catch (Exception e) {
            return null;
        }
    }

    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static Double maxNonNull(Double a, Double b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }
}
