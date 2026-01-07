package com.primebank.fraud;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.Map;

@Path("/fraud/debug")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class DebugResource {

    @Inject
    FraudThresholdService thresholdService;

    @GET
    @Path("/threshold/{key}")
    public Map<String, Object> getThreshold(@PathParam("key") String key) {
        thresholdService.load();
        return Map.of(
                "key", key,
                "value", thresholdService.get(key).toPlainString()
        );
    }

    @GET
    @Path("/thresholds")
    public Map<String, Object> listAll() {
        thresholdService.load();
        return Map.of(
                "count", thresholdService.snapshot().size(),
                "thresholds", thresholdService.snapshot()
        );
    }
}
