package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class MyPrimeRuleConfigServiceImpl implements MyPrimeRuleConfigService {

    @Inject
    DataSource dataSource;

    @Override
    public Map<String, Object> loadActiveRulesContext() {

        String sql =
            "SELECT rule_code, points, threshold1, threshold2, multiplier1, multiplier2 " +
            "FROM dbo.myprime_rule_config WHERE is_active = 1";

        Map<String, Object> rules = new HashMap<>();

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String code = rs.getString("rule_code");

                Map<String, Object> ctx = new HashMap<>();

                Integer points = (Integer) rs.getObject("points");
                if (points != null) ctx.put("points", points);

                putIfNotNull(ctx, "threshold1", rs.getBigDecimal("threshold1"));
                putIfNotNull(ctx, "threshold2", rs.getBigDecimal("threshold2"));
                putIfNotNull(ctx, "multiplier1", rs.getBigDecimal("multiplier1"));
                putIfNotNull(ctx, "multiplier2", rs.getBigDecimal("multiplier2"));

                if ("GLOBAL".equalsIgnoreCase(code)) {
                    Map<String, Object> g = new HashMap<>();
                    g.put("fraud_score", ctx.get("threshold1"));
                    g.put("susp_score", ctx.get("threshold2"));
                    g.put("ml_fraud", ctx.get("multiplier1"));
                    g.put("ml_susp", ctx.get("multiplier2"));
                    rules.put("GLOBAL", g);
                } else {
                    rules.put(code, ctx);
                }
            }

            return rules;

        } catch (Exception e) {
            throw new IllegalStateException("Failed to load dbo.myprime_rule_config", e);
        }
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }
}
