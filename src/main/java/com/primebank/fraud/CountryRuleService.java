// @ApplicationScoped
// public class CountryRuleService {

//     @Inject DataSource ds;
//     private volatile List<String> cache = List.of();

//     public List<String> getBlockedCountries() {
//         if (!cache.isEmpty()) return cache;

//         String sql = "SELECT country_code FROM fraud_country_block_rule WHERE is_active = 1";
//         List<String> list = new ArrayList<>();

//         try (var c = ds.getConnection();
//              var ps = c.prepareStatement(sql);
//              var rs = ps.executeQuery()) {

//             while (rs.next()) {
//                 list.add(rs.getString(1));
//             }
//             cache = list;
//             return list;
//         } catch (Exception e) {
//             throw new IllegalStateException("Country rules load failed", e);
//         }
//     }
// }
