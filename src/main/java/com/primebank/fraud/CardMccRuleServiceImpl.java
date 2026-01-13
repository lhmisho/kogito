package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@ApplicationScoped
public class CardMccRuleServiceImpl implements CardMccRuleService {

    @Inject
    DataSource dataSource;

    /**
     * Existing rule: generic suspicious MCC list
     */
    @Override
    public List<String> getSuspiciousMccList() {

        List<String> list = new ArrayList<>();

        String sql =
            "SELECT mcc_code " +
            "FROM fraud_mcc_rule " +
            "WHERE is_active = 1";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                list.add(rs.getString("mcc_code"));
            }

            return list;

        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load suspicious MCC list", e);
        }
    }

    /**
     * NEW rule: Product + MCC → Risk map
     * Used by DMN: PRODUCT_MCC_RISK_MAP[mcc]
     */
    @Override
    public Map<String, String> getProductMccRiskMap(String productCode) {

        Map<String, String> map = new HashMap<>();

        String sql =
            "SELECT mcc_code, risk_level " +
            "FROM fraud_mcc_product_rule " +
            "WHERE is_active = 1 " +
            "AND product_code = ?";

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
