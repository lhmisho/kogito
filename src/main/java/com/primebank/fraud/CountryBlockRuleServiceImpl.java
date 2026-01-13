package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@ApplicationScoped
public class CountryBlockRuleServiceImpl
        implements CountryBlockRuleService {

    @Inject
    DataSource dataSource;

    @Override
    public String getCountryDecision(String countryCode) {

        if (countryCode == null) {
            return null;
        }

        String sql =
            "SELECT decision_level " +
            "FROM fraud_country_block_rule " +
            "WHERE is_active = 1 " +
            "AND country_code = ?";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, countryCode);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("decision_level");
                }
            }

            return null;

        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load country block rule for country=" + countryCode, e);
        }
    }
}
