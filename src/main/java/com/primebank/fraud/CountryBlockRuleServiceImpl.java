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
public class CountryBlockRuleServiceImpl implements CountryBlockRuleService {

    @Inject
    DataSource dataSource;

    @Override
    public String getCountryDecision(String countryName) {
        if (countryName == null || countryName.trim().isEmpty()) {
            return null;
        }

        // Clean and normalize country name
        String normalizedCountryName = normalizeCountryName(countryName);
        
        // First try exact match on country_name
        String sql = "SELECT decision_level " + 
            "FROM fraud_country_block_rule " +
            "WHERE is_active = 1 " +
            "AND (UPPER(country_name) = UPPER(?) " +
                 "OR UPPER(country_code) = UPPER(?))";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, normalizedCountryName);
            ps.setString(2, normalizedCountryName); // Also check if input might be a code
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("decision_level");
                }
            }
            
            // If not found, try fuzzy match (contains)
            return fuzzyMatchCountry(normalizedCountryName);

        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load country block rule for country=" + countryName, e);
        }
    }

    private String fuzzyMatchCountry(String countryName) {
        String sql = "SELECT decision_level " +
            "FROM fraud_country_block_rule " +
            "WHERE is_active = 1 " +
            "AND UPPER(country_name) LIKE UPPER(?) ";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, "%" + countryName + "%");
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("decision_level");
                }
            }
            
            return null;

        } catch (Exception e) {
            // Log but don't throw
            System.err.println("Fuzzy country match failed: " + e.getMessage());
            return null;
        }
    }

    private String normalizeCountryName(String countryName) {
        if (countryName == null) {
            return "";
        }
        
        // Trim and convert common variations
        String normalized = countryName.trim();
        
        // Remove extra spaces
        normalized = normalized.replaceAll("\\s+", " ");
        
        // Handle common country name variations
        Map<String, String> variations = Map.of(
            "BURKINA FASSO", "BURKINA FASO",
            "THE GAMBIA", "GAMBIA",
            "REPUBLIC OF KOREA", "SOUTH KOREA",
            "DPRK", "NORTH KOREA",
            "USA", "UNITED STATES",
            "UK", "UNITED KINGDOM",
            "UAE", "UNITED ARAB EMIRATES"
        );
        
        String upperNormalized = normalized.toUpperCase();
        return variations.getOrDefault(upperNormalized, normalized);
    }

    // Optional: Add a method to get both code and name
    public Map<String, String> getCountryInfo(String countryName) {
        String sql = "SELECT country_code, country_name, decision_level "+
            "FROM fraud_country_block_rule " +
            "WHERE is_active = 1 " +
            "AND (UPPER(country_name) = UPPER(?) OR UPPER(country_code) = UPPER(?)) ";

        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            String normalized = normalizeCountryName(countryName);
            ps.setString(1, normalized);
            ps.setString(2, normalized);
            
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, String> info = new HashMap<>();
                    info.put("country_code", rs.getString("country_code"));
                    info.put("country_name", rs.getString("country_name"));
                    info.put("decision_level", rs.getString("decision_level"));
                    return info;
                }
            }
            
            return Map.of("decision_level", "NORMAL");

        } catch (Exception e) {
            return Map.of("decision_level", "NORMAL", "error", e.getMessage());
        }
    }
}