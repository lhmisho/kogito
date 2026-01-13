package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class CardMccProductRuleServiceImpl
        implements CardMccProductRuleService {

    @Inject
    DataSource dataSource;

    @Override
    public Map<String, String> getRiskByProductAndMcc(String productCode) {

        String sql =
            "SELECT mcc_code, risk_level " +
            "FROM fraud_mcc_product_rule " +
            "WHERE is_active = 1 " +
            "AND product_code = ?";

        Map<String, String> map = new HashMap<>();

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, productCode);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(
                        rs.getString("mcc_code"),
                        rs.getString("risk_level")
                    );
                }
            }

            return map;

        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load Product + MCC rules for product=" + productCode, e);
        }
    }
}
