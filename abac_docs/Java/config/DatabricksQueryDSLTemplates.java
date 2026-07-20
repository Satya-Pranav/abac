package com.onetrust.otinsightscommand.databrickssql.config;

import com.querydsl.core.QueryMetadata;
import com.querydsl.core.QueryModifiers;
import com.querydsl.sql.MySQLTemplates;
import com.querydsl.sql.SQLSerializer;

public class DatabricksQueryDSLTemplates extends MySQLTemplates {
    public DatabricksQueryDSLTemplates() {
    }

    public DatabricksQueryDSLTemplates(boolean quote) {
        super(quote);
    }

    public DatabricksQueryDSLTemplates(char escape, boolean quote) {
        super(escape, quote);
    }

    @Override
    protected void serializeModifiers(QueryMetadata metadata, SQLSerializer context) {
        QueryModifiers mod = metadata.getModifiers();
        Integer limit = mod.getLimitAsInteger();
        if (limit != null) {
            context.handle(getLimitTemplate(), limit);
        }

        Integer offset = mod.getOffsetAsInteger();
        if (offset != null) {
            context.handle(getOffsetTemplate(), offset);
        }
    }
}
