package com.onetrust.otinsightscommand.databrickssql.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "onetrust.ot-insights.silverin-migration.report")
public class SilverInMigrationProperties {
    private Map<String, List<String>> ftEntityMapping;
}
