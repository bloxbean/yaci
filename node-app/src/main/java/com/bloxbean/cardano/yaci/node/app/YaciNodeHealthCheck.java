package com.bloxbean.cardano.yaci.node.app;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.model.NodeStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class YaciNodeHealthCheck implements HealthCheck {

    @Inject
    NodeAPI nodeAPI;

    @Override
    public HealthCheckResponse call() {
        try {
            NodeStatus status = nodeAPI.getStatus();

            boolean isHealthy = status.getStatusMessage() == null ||
                    !status.getStatusMessage().toLowerCase().contains("error");

            if (isHealthy) {
                return HealthCheckResponse.up("yaci-node");
            } else {
                return HealthCheckResponse.down("yaci-node");
            }
        } catch (Exception e) {
            return HealthCheckResponse.down("yaci-node");
        }
    }
}
