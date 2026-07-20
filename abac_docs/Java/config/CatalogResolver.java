package com.onetrust.otinsightscommand.databrickssql.config;

import com.onetrust.otinsightscommand.config.DatabricksSilverinEntityTypeContext;
import com.onetrust.otinsightscommand.gateway.access.IdentityGateway;
import com.onetrust.reporting.enums.ReportableEntityType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CatalogResolver {

    private final boolean silverInEnabled;
    private final IdentityGateway identityGateway;
    private final Map<String, List<String>> entityTypeMappings;
    private final Map<String, String> entityTypeToToggleMap;
    private final String catalogName_V1;
    private final String catalogV2Name;

    public CatalogResolver(
        boolean silverInEnabled,
        @NonNull IdentityGateway identityGateway,
        Map<String, List<String>> entityTypeMappings,
        @NonNull String catalogName_V1,
        @NonNull String catalogV2Name
    ) {
        this.silverInEnabled = silverInEnabled;
        this.identityGateway = identityGateway;
        this.entityTypeMappings = entityTypeMappings;
        this.entityTypeToToggleMap = buildEntityTypeToToggleMapping(entityTypeMappings);
        this.catalogName_V1 = catalogName_V1;
        this.catalogV2Name = catalogV2Name;
    }

    public String getCatalogName() {
        ReportableEntityType entityType = DatabricksSilverinEntityTypeContext.get();
        String toggleName = entityType != null ? findToggleForEntityType(entityType) : null;

        if (!silverInEnabled || entityType == null || toggleName == null) {
            log.debug(
                "Falling back to default catalog {} (silverInEnabled={}, entityType={}, toggle={})",
                catalogName_V1, silverInEnabled, entityType, toggleName
            );
            return catalogName_V1;
        }
        try {
            boolean toggleEnabled = identityGateway.doesTenantHaveFeature(toggleName);
            if (!toggleEnabled) {
                log.debug("Feature Toggle {} not enabled for entity type {}, using default catalog: {}", toggleName, entityType, catalogName_V1);
                return catalogName_V1;
            }
            log.debug("Feature Toggle {} enabled for entity type {}, using v2 catalog: {}", toggleName, entityType, catalogV2Name);
            return catalogV2Name;
        } catch (Exception e) {
            log.warn("Failed to check toggle {} for entity type {}, using default catalog: {}", toggleName, entityType, catalogName_V1, e);
            return catalogName_V1;
        }
    }

    private Map<String, String> buildEntityTypeToToggleMapping(Map<String, List<String>> toggleToEntityTypesMap) {
        Map<String, String> entityTypeToToggleMapping = new HashMap<>();
        if (toggleToEntityTypesMap == null) {
            return entityTypeToToggleMapping;
        }
        for (Map.Entry<String, List<String>> entry : toggleToEntityTypesMap.entrySet()) {
            String toggleName = entry.getKey();
            List<String> entityTypes = entry.getValue();
            if (entityTypes == null) {
                continue;
            }
            for (String entityType : entityTypes) {
                entityTypeToToggleMapping.put(entityType, toggleName);
            }
        }
        return entityTypeToToggleMapping;
    }

    private String findToggleForEntityType(ReportableEntityType entityType) {
        return entityTypeToToggleMap.get(entityType.name());
    }
}
