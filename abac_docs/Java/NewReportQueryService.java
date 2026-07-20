package com.onetrust.otinsightscommand.service;

import com.onetrust.assertions.service.AssertionService;
import com.onetrust.framework.security.context.MsSecurityContext;
import com.onetrust.otinsightscommand.condition.DatabricksCondition;
import com.onetrust.otinsightscommand.constant.DORAConstants;
import com.onetrust.otinsightscommand.constant.DORAConstants.Worksheet;
import com.onetrust.otinsightscommand.constant.OTInsightsCommandPermission;
import com.onetrust.otinsightscommand.constant.OTInsightsConstants;
import com.onetrust.otinsightscommand.constant.ObjectType;
import com.onetrust.otinsightscommand.databrickssql.config.DatabricksSessionContext;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.aigovernance.AIGovernanceEntity;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.combined.Assessment;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.combined.ControlImplementation;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.combined.EvidenceTaskTemplate;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.combined.Inventory;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.combined.Risk;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.combined.VendorEngagement;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.compliance.ComplianceInitiative;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.compliance.EvidenceTaskImplementation;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.consent.ConsentPurposeUniqueView;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.consent.ConsentTimeSeriesTransactionHeaderDayGrain;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.consent.ConsentTimeSeriesTransactionHeaderHourGrain;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.consent.CookieTimeSeriesTransactionHeaderDayGrain;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.consent.CookieTimeSeriesTransactionHeaderHourGrain;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.dsar.DsarRequest;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.enterprisepolicy.ControlTemplate;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.globalaudit.GlobalAudit;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.globalaudit.GlobalAuditDsar;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.globalaudit.GlobalAuditIncident;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.globalaudit.GlobalAuditIssue;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.globalaudit.GlobalAuditRisk;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.indicator.IndicatorDsarRequestView;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORAEngagementContractRelatedLegalEntity;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORAEngagementContractRelatedVendor;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORAEngagementParentLegalEntity;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORAEngagementRelatedContractV2;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORAEngagementRelatedVendor;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORATab01_03;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORATab03_01;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORATab04_01;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORATab05_01;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORATab05_02;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORATab06_01;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DORATab07_01;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.inventory.dora.DoraInventory;
import com.onetrust.otinsightscommand.domain.databricks.tenant.entity.msentity.Entity_V;
import com.onetrust.otinsightscommand.domain.tenant.report.mapping.AttributePathMapper;
import com.onetrust.otinsightscommand.dto.column.ColumnMetadata;
import com.onetrust.otinsightscommand.dto.savedview.SavedReportView;
import com.onetrust.otinsightscommand.enums.FilterLogicalOperator;
import com.onetrust.otinsightscommand.enums.FilterOperator;
import com.onetrust.otinsightscommand.enums.report.DataLevelType;
import com.onetrust.otinsightscommand.enums.report.ReportAttributeDataType;
import com.onetrust.otinsightscommand.enums.report.ReportViewType;
import com.onetrust.otinsightscommand.gateway.access.IdentityGateway;
import com.onetrust.otinsightscommand.report.ArrayColumnMapper;
import com.onetrust.otinsightscommand.report.Attribute;
import com.onetrust.otinsightscommand.report.FieldCache;
import com.onetrust.otinsightscommand.report.OldQueryBuilder;
import com.onetrust.otinsightscommand.report.annotation.DefaultSort;
import com.onetrust.otinsightscommand.report.builder.ColumnResolver;
import com.onetrust.otinsightscommand.report.builder.QueryBuilder;
import com.onetrust.otinsightscommand.report.builder.remapper.ColumnRemapperService;
import com.onetrust.reporting.enums.ReportEntityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.TriFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Primary
@Conditional(DatabricksCondition.class)
@ConditionalOnProperty("onetrust.ot-insights.new-report-query-service.enabled")
public class NewReportQueryService implements ReportQueryService<QueryBuilder> {
    @PersistenceContext(unitName = "databricksPrimary")
    private EntityManager primaryEntityManager;

    @PersistenceContext(unitName = "databricksSecondary")
    private EntityManager secondaryEntityManager;

    @Autowired
    private ColumnRemapperService columnRemapperService;

    @Autowired
    private IdentityGateway identityGateway;

    @Autowired
    private ColumnMetadataService metadataService;

    @Autowired
    private AssertionService assertionService;

    /**
     * Create a {@link QueryBuilder} for a {@link SavedReportView}.
     *
     * @param report
     * @param export if true then use the secondary endpoint
     * @return
     */
    @Override
    public QueryBuilder query(SavedReportView report, boolean export) {
        Class<?> clazz = getRootClass(report);

        ObjectType rootType = DatabricksSessionContext.getCurrentObjectType();
        if (rootType != null && hasPermission(rootType.feature, export)) {
            List<ColumnMetadata> metadata;
            try {
                metadata = metadataService.getColumnMetadata(report.getReportableEntityType(), report.getSourceId(), optionalCondition(report));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load column metadata", e);
            }

            // Find all columns we need to apply the mask function to
            Map<String, String> columnPermissions = metadata.stream()
                .filter(m -> StringUtils.isNotBlank(m.getId()) && StringUtils.isNotBlank(m.getPermission()))
                .filter(m -> hasPermission(m.getPermission(), export) == false)
                .collect(Collectors.toMap(ColumnMetadata::getId, ColumnMetadata::getPermission));

            // Set masking enabled on all columns
            report.getVisibleColumns()
                .stream()
                .flatMap(SavedReportView.VisibleColumn::stream)
                .filter(c -> {
                    String id = c.getColumnId();
                    return id != null && columnPermissions.containsKey(id);
                })
                .forEach(c -> {
                    c.setMaskingPermission(columnPermissions.get(c.getColumnId()));
                });

            // Set masking enabled on all filters
            new SavedReportView.CriteriaDefinition(report.getFilterCriteria())
                .stream()
                .filter(c -> {
                    String id = c.getColumnId();
                    return id != null && columnPermissions.containsKey(id);
                })
                .forEach(c -> {
                    c.setMaskingPermission(columnPermissions.get(c.getColumnId()));
                });

            SavedReportView.SortInfo sortInfo = report.getSortInfo();
            if (sortInfo != null) {
                String id = sortInfo.getColumnName();
                String permission = columnPermissions.get(id);
                if (permission != null) {
                    sortInfo.setMaskingPermission(permission);
                }
            }
        }

        ColumnRemapperService.RemapResult remap = columnRemapperService.remap(report, clazz);
        List<SavedReportView.VisibleColumn> columns = remap.getColumns();
        Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> filters = remap.getFilters();
        SavedReportView.SortInfo sort = remap.getSort();

        if (sort == null) {
            Field ds = FieldCache.get(clazz).getDefaultSort();
            if (ds != null) {
                sort = new SavedReportView.SortInfo(ds.getName(), ds.getAnnotation(DefaultSort.class).value());
            }
        }

        List<SavedReportView.SortInfo> sorts = new LinkedList<>();
        if (sort != null) {
            sorts.add(sort);
        }

        // TODO: This should be annotation driven
        addExtraSorts(sorts, columns, clazz, export);

        addExtraFilters(report, filters, clazz);

        EntityManager entityManager = export ? secondaryEntityManager : primaryEntityManager;

        QueryBuilder builder =
            new QueryBuilder(entityManager, clazz, columns, filters, sorts, ColumnResolver::quote, remap.getRemapToOriginal(), report.isDistinct());

        return builder;
    }

    private boolean hasPermission(String permission, boolean export) {
        if (export) {
            return identityGateway.hasPermission(permission);
        } else {
            return assertionService.hasPermission(permission);
        }
    }

    private String optionalCondition(SavedReportView report) {
        /**
         * Frontend defines these to use as optionalCondition.
         *
         * https://gitlab.com/onetrust/ui/reporting-lib/-/blob/development/projects/reporting/src/lib/reports-shared/enums/reports.enum.ts#L447
         */
        @Getter
        @RequiredArgsConstructor
        enum ConditionType {
            ASSESSMENT_REPORT("assessmentReport"),
            ASSESSMENT_REQUIRED("assessmentRequired"),
            AUDIT_PDF_FINDINGS("auditPDFFindings"),
            CONTROL_IMPLEMENTATION_AS_PRIMARY_ENTITY("ControlImplementationAsPrimaryEntity"),
            EXCLUDE_MASTER_CONTROL("excludeMasterControl"),
            FINDINGS_REQUIRED("findingsRequired"),
            INCLUDE_ONLY_MASTER_CONTROLS("includeOnlyMasterControls"),
            INCLUDE_ONLY_CONTROLS_IMPLEMENTATIONS("includeOnlyControlsImplementations"),
            NEW_INVENTORY_REPORT("newInventoryReport"),
            PDF_REPORT("pdfReport"),
            PERSONAL_DATA_REQUIRED("personalDataRequired"),
            RISK_REPORT("riskReport"),
            RISK_STAGE_REQUIRED("riskStageRequired"),
            STAGE_HISTORY_REQUIRED("stageHistoryRequired");

            final String condition;
        }

        // Do the same logic as the frontend
        // https://gitlab.com/onetrust/ui/reporting-lib/-/blob/development/projects/reporting/src/lib/reports-shared/services/reporting-api/reporting-api.service.ts
        EnumSet<ConditionType> conditions = EnumSet.noneOf(ConditionType.class);

        // TODO: For PDF reports, we sometimes have section specific logic; so we need to know which section this is for
        switch (DatabricksSessionContext.getCurrentObjectType()) {
            case ISSUE:
                return null;
            case ASSET:
            case ENTITY:
            case PROCESSING_ACTIVITY:
            case VENDOR:
                if (report.getViewType() == ReportViewType.PDF) {
                    conditions.add(ConditionType.PDF_REPORT);
                }

                conditions.add(ConditionType.ASSESSMENT_REQUIRED);

                if (report.isNewInventoryReport()) {
                    conditions.add(ConditionType.NEW_INVENTORY_REPORT);
                }

                break;
            default:
                return null;
        }

        if (conditions.isEmpty()) {
            return null;
        } else {
            return conditions.stream().map(ConditionType::getCondition).collect(Collectors.joining(","));
        }
    }

    @Override
    public Class<?> getRootClass(SavedReportView report) {
        if (report.isActivityBasedReport()) {
            return getGlobalAuditRoot(report.getReportEntityType());
        }
        return switch (report.getReportEntityType()) {
            case INVENTORY -> getInventoryClass(report);
            case PIA -> Assessment.class;
            case RISK -> Risk.class;
            case ENGAGEMENT -> VendorEngagement.class;
            case DSAR -> DsarRequest.class;
            case CONTROL -> ControlImplementation.class;
            case INITIATIVE -> ComplianceInitiative.class;
            case EVIDENCE_TASK -> EvidenceTaskImplementation.class;
            case EVIDENCE_TASK_TEMPLATE -> EvidenceTaskTemplate.class;
            case CONTROL_TEMPLATE -> ControlTemplate.class;
            case ISSUE_ENTITY, OBJECTIVE -> Entity_V.class;
            case AI_GOVERNANCE -> getAIGovernanceRootClass(report);
            case INDICATOR -> getIndicatorRootClass(report);
            case COOKIE_CONSENT_STATS -> report.getDataLevelType() == DataLevelType.HOURLY
                ? CookieTimeSeriesTransactionHeaderHourGrain.class
                : CookieTimeSeriesTransactionHeaderDayGrain.class;
            case CONSENT_STATS -> report.getDataLevelType() == DataLevelType.HOURLY
                ? ConsentTimeSeriesTransactionHeaderHourGrain.class
                : ConsentTimeSeriesTransactionHeaderDayGrain.class;
            case CONSENT_PURPOSE -> ConsentPurposeUniqueView.class;
            default -> {
                if (report.getReportEntityType().isAssessment()) {
                    yield Assessment.class;
                }

                throw new UnsupportedOperationException("Can not create query for type " + report.getReportEntityType());
            }
        };
    }

    /**
     * Add extra sorting information.
     *
     * @param sorts
     * @param columns
     * @param root
     * @param export
     */
    protected void addExtraSorts(List<SavedReportView.SortInfo> sorts, List<SavedReportView.VisibleColumn> columns, Class<?> root, boolean export) {
        if (root == Assessment.class) {
            OTInsightsConstants.ColumnGroupIdentifier cgi = OTInsightsConstants.ColumnGroupIdentifier.NONE;
            for (SavedReportView.VisibleColumn c : columns) {
                if (c.isExploded() == false) {
                    continue;
                }

                String id = c.getColumnId();
                if (id.contains(AttributePathMapper.ASSESSMENT_COMMENT_COLUMNID_PREFIX)) {
                    cgi = OTInsightsConstants.ColumnGroupIdentifier.COMMENTS;
                    break;
                } else if (id.contains(AttributePathMapper.ASSESSMENT_NOTE_COLUMNID_PREFIX)) {
                    cgi = OTInsightsConstants.ColumnGroupIdentifier.NOTES;
                    break;
                } else if (id.contains(AttributePathMapper.ASSESSMENT_PROFILE_QUESTION_SCORE_COLUMNID_PREFIX)) {
                    cgi = OTInsightsConstants.ColumnGroupIdentifier.PROFILE_QUESTION_SCORES;
                    break;
                } else if (id.contains(AttributePathMapper.ASSESSMENT_SUMMARY_STAGECHANGE_COLUMNID_PREFIX)) {
                    cgi = OTInsightsConstants.ColumnGroupIdentifier.STAGE_HISTORY;
                    break;
                }
            }

            switch (cgi) {
                case COMMENTS:
                    sorts.addAll(getCommentsSortInfo());
                    break;
                case NOTES:
                    sorts.add(new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_NOTE_CREATED_DATE, true));
                    break;
                case PROFILE_QUESTION_SCORES:
                    sorts.addAll(getProfileQuestionScoresSortInfo());
                    break;
                case STAGE_HISTORY:
                    sorts.add(new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_STAGE_CYCLE_NUMBER, false));
                    break;
                case NONE:
                    break;
            }
        } else if (
            Set.of(
                CookieTimeSeriesTransactionHeaderHourGrain.class,
                CookieTimeSeriesTransactionHeaderDayGrain.class,
                ConsentTimeSeriesTransactionHeaderHourGrain.class,
                ConsentTimeSeriesTransactionHeaderDayGrain.class
            ).contains(root)
        ) {
            // For cookie stats reports, order by ID columns to ensure that order is consistent between pages
            // TODO: Should have QueryBuilder do this automatically for all report types
            // If export, clear all other sorts
            if (export) {
                sorts.clear();
            }

            // Add ID sort
            sorts.add(new SavedReportView.SortInfo(OTInsightsConstants.ID, true));

            sorts.add(new SavedReportView.SortInfo("key", true));

            // If on the purpose tab, also sort by purpose
            if (columns.stream().filter(SavedReportView.VisibleColumn::isExploded).anyMatch(c -> c.getColumnId().startsWith("details."))) {
                sorts.add(new SavedReportView.SortInfo("details.purpose", true));
            }
        }
    }

    private List<SavedReportView.SortInfo> getCommentsSortInfo() {
        return List.of(
            new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_COMMENT_SECTION_SEQUENCE, true),
            new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_COMMENT_QUESTION_SEQUENCE, true),
            new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_COMMENT_CREATED_DATE, true)
        );
    }

    private List<SavedReportView.SortInfo> getProfileQuestionScoresSortInfo() {
        return List.of(
            new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_PROFILE_QUESTION_SCORE_PROFILE_ID, true),
            new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_PROFILE_QUESTION_SCORE_SECTION_SEQUENCE, true),
            new SavedReportView.SortInfo(OTInsightsConstants.ASSESSMENT_PROFILE_QUESTION_SCORE_QUESTION_SEQUENCE, true)
        );
    }

    /**
     * Add extra filters to a report.
     *
     * @param report
     * @param filters
     * @param rootClass
     */
    public void addExtraFilters(
        SavedReportView report,
        Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> filters,
        Class<?> rootClass
    ) {
        List<SavedReportView.CriteriaDefinition> and = filters.computeIfAbsent(FilterLogicalOperator.AND, k -> new LinkedList<>());

        SavedReportView.CriteriaDefinition sourceFilter = createSourceFilter(report.getSourceId(), rootClass);
        if (sourceFilter != null) {
            and.add(sourceFilter);
        }
    }

    /**
     * Create a filter for sourceID.
     * <p>
     * TODO: This should instead work like the organization filter which is handled in QueryBuilder using annnotations.
     *
     * @param sourceID
     * @param rootClass
     * @return
     */
    public SavedReportView.CriteriaDefinition createSourceFilter(String sourceID, Class<?> rootClass) {
        // TODO: Should treat the sourceID filter like orgID
        if (StringUtils.isEmpty(sourceID) || OTInsightsConstants.EMPTY_UUID.toString().equals(sourceID)) {
            return null;
        }

        Field f = FieldCache.get(rootClass).getSourceID();
        if (f == null) {
            log.warn("Can not create a sourceID filter for {} as {} does not have a @SourceID field", sourceID, rootClass);
            return null;
        }

        SavedReportView.CriteriaDefinition sourceFilter = new SavedReportView.CriteriaDefinition(
            f.getName(),
            ReportAttributeDataType.TEXT,
            FilterOperator.EQ,
            sourceID
        );

        return sourceFilter;
    }

    /**
     * Convenience method to build and execute a query.
     *
     * @param report
     * @param export
     * @return
     */
    @Override
    public List<Object[]> execute(SavedReportView report, boolean export) {
        QueryBuilder builder = query(report, export);
        return execute(builder, builder.build());
    }

    /**
     * Execute a query from {@link QueryBuilder#build()}.
     * <p>
     * Pagination restrictions should be applied before calling this.
     *
     * @param builder
     * @param query
     * @return
     */
    @Override
    public List<Object[]> execute(QueryBuilder builder, TypedQuery<Object[]> query) {
        // query.getResultStream has problems with the ResultSet closing
        List<Object[]> results = query.getResultList();
        ListIterator<?> iterator = results.listIterator();

        @SuppressWarnings("unchecked")
        ListIterator<Object[]> rowIterator = (ListIterator<Object[]>) iterator;
        while (iterator.hasNext()) {
            Object[] row;
            Object rowValue = iterator.next();
            if (rowValue == null) {
                // when there are no values for a row just set the row to empty array with size equal to visible columns
                row = new Object[builder.getSelections().size()];
                rowIterator.set(row);
            } else if (rowValue.getClass().isArray()) {
                row = (Object[]) rowValue;
            } else {
                // If only one column is selected, the result type is not Object[] but Object.
                row = new Object[]{rowValue};
                rowIterator.set(row);
            }

            builder.mapRow(row);
        }

        return results;
    }

    /**
     * Find the max of a {@link Date} field.
     *
     * @param report
     * @param field
     * @return
     */
    public Date getMaxDate(SavedReportView report, String field) {
        Class<?> rootClass = getRootClass(report);

        CriteriaBuilder builder = primaryEntityManager.getCriteriaBuilder();
        CriteriaQuery<Date> query = builder.createQuery(Date.class);
        Root<?> root = query.from(rootClass);

        Field f = FieldCache.get(rootClass).getField(field);
        if (f == null) {
            throw new UnsupportedOperationException(rootClass + " does not have a field " + field);
        }

        query.multiselect(builder.max(root.get(f.getName())));
        return primaryEntityManager.createQuery(query).getSingleResult();
    }

    /**
     * Create a {@link Predicate} from a criteria map to add to a {@link OldQueryBuilder}.
     * <p>
     * Note that the created predicate is not added to the {@link OldQueryBuilder} automatically.
     *
     * @param builder
     * @param filters
     * @param groupUncoalesced
     * @return
     */
    protected Predicate where(
        OldQueryBuilder builder,
        Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> filters,
        boolean groupUncoalesced
    ) {
        if (MapUtils.isEmpty(filters)) {
            return null;
        }

        Predicate predicate = null;
        for (Map.Entry<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> entry : filters.entrySet()) {
            List<Predicate> predicates = new LinkedList<>();

            Stream<SavedReportView.CriteriaDefinition> stream = entry.getValue().stream();

            stream.map(c -> toPredicate(builder, c, groupUncoalesced))
                .filter(Objects::nonNull)
                .forEach(predicates::add);

            if (predicates.isEmpty()) {
                continue;
            }

            Predicate p;
            if (entry.getKey() == FilterLogicalOperator.OR) {
                p = builder.getCriteriaBuilder().or(predicates.toArray(Predicate[]::new));
            } else {
                p = builder.getCriteriaBuilder().and(predicates.toArray(Predicate[]::new));
            }

            if (predicate == null) {
                predicate = p;
            } else {
                predicate = builder.getCriteriaBuilder().and(predicate, p);
            }
        }

        return predicate;
    }

    /**
     * Check if type can be assigned to any class in assignableTo.
     *
     * @param type
     * @param assignableTo
     * @return
     */
    protected boolean isAssignableToAny(Class<?> type, Class<?>... assignableTo) {
        for (Class<?> a : assignableTo) {
            if (a.isAssignableFrom(type)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if type is an integer number type.
     *
     * @param type
     * @return
     */
    protected boolean isInt(Class<?> type) {
        return isAssignableToAny(
            type,
            byte.class,
            Byte.class,
            short.class,
            Short.class,
            int.class,
            Integer.class,
            long.class,
            Long.class
        );
    }

    /**
     * Check if type is a floating point number type.
     *
     * @param type
     * @return
     */
    protected boolean isDouble(Class<?> type) {
        return isAssignableToAny(
            type,
            float.class,
            Float.class,
            double.class,
            Double.class
        );
    }

    /**
     * Coerce a String to the type of a column.
     * <p>
     * This is needed because
     * {@link #toPredicate(com.onetrust.otinsightscommand.report.QueryBuilder, com.onetrust.otinsightscommand.dto.savedview.SavedReportView.CriteriaDefinition)}
     * uses {@link CriteriaBuilder#literal} to work around generic compilation errors.
     * <p>
     * Solve that and this method goes away.
     *
     * @param column
     * @param value
     * @return
     */
    protected Object coerceValue(Expression<?> column, String value) {
        Class<?> type = column.getJavaType();

        if (type == String.class) {
            return value;
        } else if (isInt(type)) {
            return Long.valueOf(value);
        } else if (isDouble(type)) {
            return Double.valueOf(value);
        } else if (Date.class.isAssignableFrom(type)) {
            return Date.from(Instant.parse(value));
        } else if (type.isAssignableFrom(UUID.class)) {
            return UUID.fromString(value);
        } else {
            log.warn("Cannot coerce to type {}", type);
            return value;
        }
    }

    /**
     * Coerce all Strings to the type of a column using
     * {@link #coerceValue(jakarta.persistence.criteria.Expression, java.lang.String)}.
     *
     * @param values
     * @param column
     * @return
     */
    protected List<Object> coerceValues(Path<?> column, String[] values) {
        if (values == null) {
            return null;
        }

        List<Object> l = new ArrayList<>(values.length);
        for (String value : values) {
            l.add(coerceValue(column, value));
        }

        return l;
    }

    private String lambdaName(String lambda, boolean matchValue, boolean matchValueKey) {
        if (matchValue) {
            return lambda + "_value";
        } else if (matchValueKey) {
            return lambda + "_valueKey";
        }

        return lambda;
    }

    /**
     * Convert a single {@link SavedReportView.CriteriaDefinition} into a {@link Predicate}.
     * <p>
     * If the criteria has nested criteria, it will recurse into them.
     *
     * @param builder
     * @param criteria
     * @param groupUncoalesced
     * @return
     */
    @Override
    public Predicate toPredicate(OldQueryBuilder builder, SavedReportView.CriteriaDefinition criteria, boolean groupUncoalesced) {
        Predicate predicate = where(builder, criteria.getFilterCriteria(), groupUncoalesced);
        if (predicate != null) {
            return predicate;
        }

        CriteriaBuilder cb = builder.getCriteriaBuilder();
        Path<?> column = builder.resolveColumnPath(criteria.getColumnId(), criteria.getEntityType());
        Path<?> field = column;

        String[] values = criteria.getValues();
        String[] valueKeys = criteria.getValueKeys();
        String[] filter;

        boolean matchValue, matchValueKey;
        matchValue = matchValueKey = false;
        if (Attribute.class.isAssignableFrom(column.getJavaType())) {
            if (valueKeys != null && valueKeys.length > 0 && StringUtils.isNotEmpty(valueKeys[0])) {
                column = column.get("valueKey");
                filter = valueKeys;
                matchValueKey = true;
            } else {
                column = column.get("value");
                filter = values;
                matchValue = true;
            }
        } else {
            filter = values;
        }

        final Path<?> path = column;

        boolean coalesce = builder.shouldCoalesce(criteria.getColumnId(), field);

        // Use a Supplier to work around sonar
        Supplier<Expression<?>[]> getCoerced = () -> Stream.concat(
                Stream.of(new ArrayColumnMapper<>(field).toArray(cb)),
                coerceValues(path, filter).stream().map(cb::literal)
            )
            .toList()
            .toArray(Expression[]::new);

        return toPredicate(builder, criteria, coalesce, groupUncoalesced, matchValue, matchValueKey, getCoerced, column, field, filter, values);
    }

    /**
     * Split from {@link #toPredicate(QueryBuilder, SavedReportView.CriteriaDefinition)} because of sonar.
     *
     * @param builder
     * @param criteria
     * @param coalesce
     * @param matchValue
     * @param matchValueKey
     * @param getCoerced
     * @param path
     * @param column
     * @param field
     * @param filter
     * @param values
     * @return
     */
    private Predicate toPredicate(
        OldQueryBuilder builder,
        SavedReportView.CriteriaDefinition criteria,
        boolean coalesce,
        boolean groupUncoalecesd,
        boolean matchValue,
        boolean matchValueKey,
        Supplier<Expression<?>[]> getCoerced,
        Path<?> column,
        Path<?> field,
        String[] filter,
        String[] values
    ) {

        if (coalesce == false && groupUncoalecesd) {
            // If the field is not coalesced, add it to the group by
            builder.groupBy(column);
        }

        CriteriaBuilder cb = builder.getCriteriaBuilder();
        return toPredicate(criteria, coalesce, cb, matchValue, matchValueKey, getCoerced, filter, field, column, values);
    }

    /**
     * Convert a {@link SavedReportView.CriteriaDefinition} to a {@link Predicate}.
     *
     * @param criteria
     * @param coalesce
     * @param cb
     * @param matchValue
     * @param matchValueKey
     * @param getCoerced
     * @param filter
     * @param field
     * @param column
     * @param values
     * @return
     * @throws UnsupportedOperationException
     * @throws NumberFormatException
     */
    @SuppressWarnings("unchecked")
    public Predicate toPredicate(
        SavedReportView.CriteriaDefinition criteria,
        boolean coalesce,
        CriteriaBuilder cb,
        boolean matchValue,
        boolean matchValueKey,
        Supplier<Expression<?>[]> getCoerced,
        String[] filter,
        Path<?> field,
        Path<?> column,
        String[] values
    ) throws UnsupportedOperationException, NumberFormatException {
        // For working around compilation errors due to generic mismatch
        Function<BiFunction<Expression<Comparable>, Comparable, Predicate>, Predicate> rawTypePredicate = (comparison) -> {
            BiFunction<Expression, Object, Predicate> f = (BiFunction) comparison;
            return f.apply(column, coerceValues(column, filter).get(0));
        };

        // Takes a literal Expression instead of using in.get(0)
        BiFunction<BiFunction<Expression<Comparable>, Comparable, Predicate>, Comparable, Predicate> rawTypePredicate2 = (comparison, value) -> {
            return comparison.apply((Expression<Comparable>) column, value);
        };

        // Same as rawTypePredicate, but arity 3 instead of 2
        Function<TriFunction<Expression<Comparable>, Comparable, Comparable, Predicate>, Predicate> rawTypePredicate3 = (comparison) -> {
            TriFunction<Expression, Object, Object, Predicate> f = (TriFunction) comparison;
            List<Object> coerce = coerceValues(column, filter);
            return f.apply(column, coerce.get(0), coerce.get(1));
        };

        switch (criteria.getOperator()) {
            case EQ:
            case NE:
                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(lambdaName("exists_in", matchValue, matchValueKey), boolean.class, getCoerced.get());
                    if (criteria.getOperator() == FilterOperator.NE) {
                        return cb.isFalse(lambda);
                    } else {
                        return cb.isTrue(lambda);
                    }
                }

                CriteriaBuilder.In<Object> p = cb.in(column);
                for (Object value : coerceValues(column, filter)) {
                    p = p.value(value);
                }

                if (criteria.getOperator() == FilterOperator.NE) {
                    return p.not();
                }

                return p;
            case LT:
                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(lambdaName("exists_lt", matchValue, matchValueKey), boolean.class, getCoerced.get());
                    return cb.isTrue(lambda);
                }

                return rawTypePredicate.apply(cb::lessThan);
            case LE:
                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(lambdaName("exists_le", matchValue, matchValueKey), boolean.class, getCoerced.get());
                    return cb.isTrue(lambda);
                }

                return rawTypePredicate.apply(cb::lessThanOrEqualTo);
            case GT:
                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(lambdaName("exists_gt", matchValue, matchValueKey), boolean.class, getCoerced.get());
                    return cb.isTrue(lambda);
                }

                return rawTypePredicate.apply(cb::greaterThan);
            case GE:
                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(lambdaName("exists_ge", matchValue, matchValueKey), boolean.class, getCoerced.get());
                    return cb.isTrue(lambda);
                }

                return rawTypePredicate.apply(cb::greaterThanOrEqualTo);
            case BW:
                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(lambdaName("exists_bw", matchValue, matchValueKey), boolean.class, getCoerced.get());
                    return cb.isTrue(lambda);
                }

                return rawTypePredicate3.apply(cb::between);
            case EMPTY:
                if (coalesce) {
                    // cardinality is the array length
                    Expression<Long> cardinality = cb.function("cardinality", Long.class, new ArrayColumnMapper<>(field).toArray(cb));
                    return cb.equal(cardinality, 0);
                }

                return cb.isNull(column);
            case RE:
                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(lambdaName("exists_re", matchValue, matchValueKey), boolean.class, getCoerced.get());
                    return cb.isTrue(lambda);
                }

                return cb.isTrue(cb.function("REGEXP_LIKE", Boolean.class, column, cb.literal(values[0])));
            case LAST_N_DAYS:
                long days = Long.parseLong(values[0]);
                Instant toDate;
                Instant fromDate;

                TimeZone timezone = MsSecurityContext.getCurrentUserDetails().getTimeZone();
                if (timezone == null) {
                    log.warn("timeZoneOffsetInMinutes is missing request header, using UTC as default");
                    timezone = TimeZone.getTimeZone(ZoneOffset.UTC);
                }

                ZonedDateTime now = ZonedDateTime.now(timezone.toZoneId());
                if (days > 0) {
                    // Handle past dates, including today. [now - days, now]
                    toDate = now.plusDays(1).with(LocalTime.MIN).toInstant();
                    fromDate = now.minusDays(days).with(LocalTime.MIN).toInstant();
                } else {
                    // Handle future dates, excluding today. [now + 1 day, now + days]
                    fromDate = now.plusDays(1).with(LocalTime.MIN).toInstant();
                    toDate = now.plusDays((-days) + 1).with(LocalTime.MIN).toInstant();
                }

                Comparable<?> to;
                Comparable<?> from;
                if (ReportAttributeDataType.TEXT == criteria.getDataType()) {
                    from = DateTimeFormatter.ISO_LOCAL_DATE.format(fromDate);
                    to = DateTimeFormatter.ISO_LOCAL_DATE.format(toDate);
                } else {
                    from = Date.from(fromDate);
                    to = Date.from(toDate);
                }

                if (coalesce) {
                    Expression<Boolean> lambda = cb.function(
                        lambdaName("exists_gelt", matchValue, matchValueKey),
                        boolean.class,
                        new ArrayColumnMapper<>(field).toArray(cb),
                        cb.literal(from),
                        cb.literal(to)
                    );
                    return cb.isTrue(lambda);
                }

                Predicate gte = rawTypePredicate2.apply(cb::greaterThanOrEqualTo, from);
                Predicate lt = rawTypePredicate2.apply(cb::lessThan, to);
                return cb.and(gte, lt);
            default:
                throw new UnsupportedOperationException("Can not builder filter for operator " + criteria.getOperator());
        }
    }

    private Class<?> getGlobalAuditRoot(ReportEntityType reportEntityType) {
        return switch (reportEntityType) {
            case RISK -> GlobalAuditRisk.class;
            case INCIDENT -> GlobalAuditIncident.class;
            case DSAR -> GlobalAuditDsar.class;
            case ISSUE_ENTITY -> GlobalAuditIssue.class;
            default -> GlobalAudit.class;
        };
    }

    private Class<?> getIndicatorRootClass(SavedReportView report) {
        return IndicatorDsarRequestView.class;
    }

    private Class<?> getAIGovernanceRootClass(SavedReportView report) {
        if (ReportViewType.PDF.equals(report.getViewType())) {
            return AIGovernanceEntity.class;
        }
        return Entity_V.class;
    }

    private Class<?> getInventoryClass(SavedReportView report) {
        // Fetch respective inventory class for DORA report based on Tab
        if (DORAConstants.isDoraTemplate(report.getTemplateNameKey())) {
            Optional<List<String>> headers = Optional.ofNullable(report.getVisibleColumns())
                .filter(l -> l.isEmpty() == false)
                .map(l -> l.get(0))
                .map(SavedReportView.VisibleColumn::getColumnHeader)
                .filter(l -> l.isEmpty() == false);
            if (headers.isPresent()) {
                String tab = headers.get().get(0);
                if (tab == null) {
                    return DoraInventory.class;
                }

                Set<String> engagementRelatedContractTabs =
                    Set.of(Worksheet.TAB_02_01, Worksheet.TAB_02_03, Worksheet.TAB_03_02, Worksheet.TAB_03_03);
                boolean hasOneToManyNthPartyFeature =
                    identityGateway.doesTenantHaveFeature(OTInsightsCommandPermission.TPRM_NTH_PARTY_ONE_TO_MANY_FT_NAME);

                if (tab.startsWith(Worksheet.TAB_01_02)) {
                    return DORAEngagementParentLegalEntity.class;
                } else if (tab.startsWith(Worksheet.TAB_01_03)) {
                    return DORATab01_03.class;
                } else if (tab.startsWith(Worksheet.TAB_06_01)) {
                    return DORATab06_01.class;
                } else if (startsWithAny(tab, engagementRelatedContractTabs)) {
                    return DORAEngagementRelatedContractV2.class;
                } else if (tab.startsWith(Worksheet.TAB_07_01)) {
                    return DORATab07_01.class;
                } else if (tab.startsWith(Worksheet.TAB_02_02)) {
                    return DORAEngagementContractRelatedLegalEntity.class;
                } else if (tab.startsWith(Worksheet.TAB_03_01)) {
                    return DORATab03_01.class;
                } else if (tab.startsWith(Worksheet.TAB_04_01)) {
                    return DORATab04_01.class;
                } else if (tab.startsWith(Worksheet.TAB_05_01)) {
                    return hasOneToManyNthPartyFeature ? DORATab05_01.class : DORAEngagementRelatedVendor.class;
                } else if (tab.startsWith(Worksheet.TAB_05_02)) {
                    return hasOneToManyNthPartyFeature ? DORATab05_02.class : DORAEngagementContractRelatedVendor.class;
                } else {
                    return DoraInventory.class;
                }
            }
        }

        return Inventory.class;
    }

    private boolean startsWithAny(String tab, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (tab.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }
}
