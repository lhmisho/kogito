package com.primebank.fraud;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.Map;

@Path("/admin/fraud")
@RolesAllowed("FRAUD_ADMIN")
public class FraudAdminResource {
    
    @Inject
    CardFraudThresholdService thresholdService;
    
    @POST
    @Path("/refresh-cache")
    @Produces(MediaType.APPLICATION_JSON)
    public Response refreshCache() {
        try {
            if (thresholdService instanceof CardFraudThresholdServiceImpl) {
                ((CardFraudThresholdServiceImpl) thresholdService).refreshAllCaches();
            }
            
            return Response.ok(Map.of(
                "status", "success",
                "message", "Cache refreshed successfully",
                "timestamp", LocalDateTime.now().toString()
            )).build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of(
                    "status", "error",
                    "message", "Failed to refresh cache: " + e.getMessage()
                )).build();
        }
    }
    
    @GET
    @Path("/cache-status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCacheStatus() {
        if (thresholdService instanceof CardFraudThresholdServiceImpl) {
            CardFraudThresholdServiceImpl service = (CardFraudThresholdServiceImpl) thresholdService;
            
            return Response.ok(Map.of(
                "threshold_count", service.getAllThresholds().size(),
                "mcc_rules_count", service.getSuspiciousMccList().size(),
                "cache_enabled", true,
                "last_refresh", LocalDateTime.now().toString()
            )).build();
        }
        
        return Response.ok(Map.of("cache_enabled", false)).build();
    }
}