package com.onetrust.otinsightscommand.report.builder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.onetrust.otinsightscommand.config.ApplicationContextProvider;
import com.onetrust.otinsightscommand.databrickssql.config.DatabricksSQLConfig;
import com.onetrust.otinsightscommand.databrickssql.config.DatabricksSessionContext;
import com.onetrust.otinsightscommand.dto.savedview.SavedReportView;
import com.onetrust.otinsightscommand.enums.FilterLogicalOperator;
import com.onetrust.otinsightscommand.enums.report.ReportAttributeDataType;
import com.onetrust.otinsightscommand.report.AssessmentQuestionRoot;
import com.onetrust.otinsightscommand.report.AttributeListContextReference;
import com.onetrust.otinsightscommand.report.AttributeListTypeReference;
import com.onetrust.otinsightscommand.report.AttributeWithTranslationListTypeReference;
import com.onetrust.otinsightscommand.report.FieldCache;
import com.onetrust.otinsightscommand.report.annotation.AdvancedHyperlink;
import com.onetrust.otinsightscommand.report.annotation.Aggregate;
import com.onetrust.otinsightscommand.report.annotation.Coalesce;
import com.onetrust.otinsightscommand.report.annotation.Hyperlink;
import com.onetrust.otinsightscommand.report.annotation.OrgFilterReplacement;
import com.onetrust.otinsightscommand.report.annotation.SortCoalescedBy;
import com.onetrust.otinsightscommand.report.builder.org.DefaultOrgFilterCreator;
import com.onetrust.otinsightscommand.report.builder.org.OrgFilterCreator;
import com.onetrust.otinsightscommand.util.ListView;
import com.onetrust.otinsightscommand.util.hibernate.HibernateUtils;
import com.onetrust.reporting.enums.ReportAttributeEntityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.metamodel.Attribute;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.hibernate.Session;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.JpaCteCriteria;
import org.hibernate.query.criteria.JpaPath;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.sqm.NodeBuilder;
import org.hibernate.query.sqm.SqmQuerySource;
import org.hibernate.query.sqm.tree.cte.SqmCteStatement;
import org.hibernate.query.sqm.tree.from.SqmFromClause;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.query.sqm.tree.select.SqmSelectClause;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;

@Data
@Slf4j
public final class QueryBuilder implements BuildableQuery {
    static final char NUL = 0;

    @Data
    public static class HyperlinkInfo {
        private final String columnID;
        private final Hyperlink hyperlink;
        private final AdvancedHyperlink advancedHyperlink;

        private final List<String> fields;
        private final List<Class<?>> types;
        private final Expression<?> value;
        private final Map<String, Boolean> structFields;
    }

    @Data
    public static class MapperInfo {
        private final ColumnMappers.ExpressionMapper exprMapper;
        private final ColumnMappers.Mapper mapper;
        private final Class<?> type;
    }

    public static boolean isEligibleTypeForExtract(
        Class<?> type
    ) {
        boolean isAttributeListReference = (AttributeListTypeReference.class.isAssignableFrom(type)
            || AttributeListContextReference.class.isAssignableFrom(type)
            || AttributeWithTranslationListTypeReference.class.isAssignableFrom(type));

        return (TypeReference.class.isAssignableFrom(type) && isAttributeListReference)
            || AssessmentQuestionRoot.class.isAssignableFrom(type);
    }

    /**
     * The EntityManager used to create and run the query.
     */
    private final EntityManager manager;

    /**
     * The class representing the root table to query from.
     */
    private final Class<?> rootClass;

    /**
     * The columns which were resolved and will be selected.
     */
    private final List<SavedReportView.VisibleColumn> columns;

    /**
     * The filters which will be applied.
     */
    private final Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> filters;

    /**
     * The columns to sort by.
     */
    private final List<SavedReportView.SortInfo> sort;

    /**
     * Function to quote an identifier.
     */
    private final UnaryOperator<String> quote;

    /**
     * Map of remapped columnID to original columnID.
     */
    private final Map<String, String> remappedColumnIDs;

    /**
     * Alias of builder
     * <p>
     * This exists as HibernateCriteriaBuilder has different ABI for some methods.
     */
    private final CriteriaBuilder cbuilder;

    /**
     * For hibernate specific features, such as CTEs.
     */
    private final HibernateCriteriaBuilder builder;

    /**
     * The CTE that selects all the data.
     */
    private final JpaCriteriaQuery<Tuple> cteQuery;

    /**
     * The root table in the CTE.
     */
    private final JpaRoot<?> root;

    /**
     * This is used only to resolve columns for the CTE, not the outer query.
     */
    private final ColumnResolver resolver;

    /**
     * The outer query.
     */
    private final JpaCriteriaQuery<Object[]> query;

    /**
     * The CTE query selects from.
     */
    private final JpaCteCriteria<Tuple> queryWith;

    /**
     * The CTE as a JpaRoot.
     */
    private final JpaRoot<Tuple> queryRoot;

    /**
     * Items in cteSelections that need to be added to groupBy.
     */
    private final List<String> cteGroupBy = new LinkedList<>();

    /**
     * Map of columnID to HyperlinkInfo.
     */
    private final Map<String, HyperlinkInfo> hyperlinkInfo = new TreeMap<>();

    /**
     * Map of columnID to if masking is enabled.
     */
    private final Map<String, Boolean> maskingEnabled = new TreeMap<>();

    /**
     * Map storing if columns in a given table should be coalesced or exploded.
     */
    private final Map<From<?, ?>, Boolean> coalescedTables = new LinkedHashMap<>();

    /**
     * Selected columnIDs.
     */
    private final List<String> selectedColumns = new LinkedList<>();

    /**
     * Columns selected due to their presence in columns.
     */
    private final List<Expression<?>> selections = new LinkedList<>();

    /**
     * Extra columns selected to pull in hyperlink data.
     */
    private final List<Expression<?>> hyperlinkSelections = new LinkedList<>();

    /**
     * When ABAC masking is enabled, this column contains if the value should be masked.
     */
    private final List<Expression<?>> maskingSelections = new LinkedList<>();

    /**
     * We have to select the columns we sort by, so store them here.
     */
    private final List<Expression<?>> sortSelections = new LinkedList<>();

    /**
     * We have to select the columns we order by, so store them here.
     */
    private final List<Expression<?>> orderSelections = new ArrayList<>();

    /**
     * Everything that will be selected by the outer query.
     */
    private final ListView<Expression<?>> select = new ListView<>(selections, hyperlinkSelections, maskingSelections);

    /**
     * Map of columnID to starting column index for hyperlink data.
     */
    private final Map<String, Integer> hyperlinkIndexes = new TreeMap<>();

    /**
     * Map of columnID to starting column index for masking data.
     */
    private final Map<String, Integer> maskingIndexes = new TreeMap<>();

    /**
     * Mapping information for columns selected from columns.
     */
    private final List<MapperInfo> selectMappers = new LinkedList<>();

    /**
     * Mapping information for columns selected for hyperlink data.
     */
    private final List<MapperInfo> hyperlinkMappers = new LinkedList<>();

    /**
     * Mapping information for columns selected for masking data.
     */
    private final List<MapperInfo> maskingMappers = new LinkedList<>();

    /**
     * Mapping information for extra columns selected (example: ORDER BY clause).
     */
    private final List<MapperInfo> extraMappers = new LinkedList<>();

    /**
     * All mapping information.
     */
    private final List<MapperInfo> mappers = new ListView<>(selectMappers, hyperlinkMappers, maskingMappers, extraMappers);

    /**
     * Predicates to apply in the WHERE clause.
     */
    private final Map<FilterLogicalOperator, List<Predicate>> where = new EnumMap<>(FilterLogicalOperator.class);

    /**
     * Predicates to apply in the HAVING clause.
     */
    private final Map<FilterLogicalOperator, List<Predicate>> having = new EnumMap<>(FilterLogicalOperator.class);

    /**
     * What the outer query will be grouped by.
     */
    private final List<Expression<?>> groupBy = new LinkedList<>();

    private final boolean distinct;

    /**
     * Predicates to apply in the inner WHERE clause.
     */
    private final List<Predicate> predicates = new ArrayList<>();

    /**
     * Map to hold the list of predicates for given exploded table.
     */
    private final Map<From<?, ?>, List<Predicate>> explodedTablePredicates = new HashMap<>();

    /**
     * We can't do column masking of oauth is not being used.
     */
    private final boolean oauth = ApplicationContextProvider.getApplicationContext().getBean(DatabricksSQLConfig.class).isOauth();

    public QueryBuilder(
        EntityManager manager,
        Class<?> rootClass,
        List<SavedReportView.VisibleColumn> columns,
        Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> filters,
        List<SavedReportView.SortInfo> sort,
        UnaryOperator<String> quote,
        Map<String, String> remappedColumnIDs,
        boolean distinct
    ) {
        this.manager = manager;
        this.rootClass = rootClass;
        this.columns = columns == null ? List.of() : columns.stream().map(SavedReportView.VisibleColumn::new).toList();
        this.filters = filters == null ? Map.of() : SavedReportView.CriteriaDefinition.clone(filters);
        this.sort = sort == null ? List.of() : sort.stream().map(SavedReportView.SortInfo::new).toList();
        this.quote = quote;
        this.remappedColumnIDs = remappedColumnIDs == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(remappedColumnIDs));
        this.distinct = distinct;

        this.cbuilder = this.builder = manager.unwrap(Session.class).getSessionFactory().getCriteriaBuilder();
        this.query = builder.createQuery(Object[].class);
        this.cteQuery = builder.createTupleQuery();
        this.root = cteQuery.from(rootClass);
        this.resolver = new ColumnResolver(builder, root, quote);

        // Columns in the root are always exploded
        coalescedTables.put(root, false);

        buildCTE();

        this.queryWith = query.with(cteQuery);
        this.queryRoot = query.from(queryWith);

        buildQuery();
    }

    private void buildCTE() {
        columns.forEach(this::cteSelect);

        cteSelect(filters);

        for (SavedReportView.SortInfo s : sort) {
            String column = s.getColumnName();
            try {
                resolver.resolveColumn(column);
            } catch (ColumnResolver.NoSuchColumnException e) {
                throw new RuntimeException(
                    "Sort column " + column + " for root " + rootClass +
                        " is not a simple column and was not selected, therefore it can not be resolved.",
                    e
                );
            }
        }

        List<Expression<?>> group = new LinkedList<>();
        Collection<ColumnResolver.Resolved> resolved = resolver.getResolved().values();
        List<Selection<?>> select = new ArrayList<>(resolved.size() + coalescedTables.size());
        resolved.forEach(r -> {
            Expression<?> value = r.getExpression();
            Expression<?> id = r.getExpressionID();

            Expression<?> hyperlink;
            HyperlinkInfo info = hyperlinkInfo.get(r.getCacheKey());
            if (info == null) {
                hyperlink = cbuilder.function("null", Object.class);
            } else {
                hyperlink = info.getValue();
            }

            Expression<?> shouldMask = shouldMask(r);

            Expression<?> maskedValue = builder.function("if", Object.class, shouldMask, builder.function("null", Object.class), value);

            @SuppressWarnings("unchecked")
            Function<Expression<?>, Expression<Object>> unchecked = e -> (Expression) e;
            Optional<Coalesce> annotation = Optional.ofNullable(r.getFields().peek().getAnnotation(Coalesce.class));

            // Use a LinkedHashMap to make sure array_sort works as expected
            LinkedHashMap<String, Expression<Object>> structMap = new LinkedHashMap<>();

            // Handle sort fields from SortCoalescedBy annotation
            handleSortCoalescedBy(r, structMap);

            structMap.put("id", unchecked.apply(id));
            structMap.put("value", unchecked.apply(maskedValue));
            structMap.put("hyperlink", unchecked.apply(hyperlink));
            structMap.put("masked", unchecked.apply(shouldMask));

            Expression<?> struct = HibernateUtils.createStruct(cbuilder, structMap, false);

            List<Predicate> predicates = r.getPredicates();
            if (predicates.isEmpty() == false) {
                struct = HibernateUtils.caseWhen(cbuilder, struct, predicates);
            } else {
                struct = HibernateUtils.caseWhen(cbuilder, struct, List.of(value.isNotNull()));
            }

            // Pivot columns are always coalesced, even if the table they are from is not.
            Expression<?> out;
            if (r.isArray() == false && (shouldCoalesce(r) || r.isPivot())) {
                String agg = annotation.map(Coalesce::distinct).orElse(true) ? "array_agg_distinct" : "array_agg";

                Aggregate.Function function = Optional.ofNullable(r.getFields().peek().getAnnotation(Aggregate.class))
                    .map(Aggregate::value)
                    .orElse(Aggregate.Function.ARRAY);

                switch (function) {
                    case ARRAY:
                        out = cbuilder.function(agg, String.class, struct);
                        out = HibernateUtils.filterFieldNonNull(builder, out, "value");
                        boolean isAscending = Optional.ofNullable(r.getTables().peek().getJavaType().getAnnotation(SortCoalescedBy.class))
                            .map(SortCoalescedBy::ascending).orElse(true);

                        out = cbuilder.function("sort_array", String.class, out, cbuilder.literal(isAscending));
                        break;
                    case SUM:
                        @SuppressWarnings("unchecked")
                        Expression<?> expr = cbuilder.sum((Expression) value);
                        out = expr;
                        break;
                    default:
                        throw new UnsupportedOperationException("Unsupported function " + function);
                }
            } else {
                out = struct;
                group.add(out);
            }

            String alias = r.getAlias();
            out = HibernateUtils.withCommentPrefix(cbuilder, out, alias);

            select.add(out.alias(alias));
        });

        // For each exploded table, we need to group by the ID fields
        coalescedTables.forEach((table, coalesce) -> {
            if (coalesce) {
                return;
            }

            Expression<?> tid = HibernateUtils.createIDStruct(cbuilder, table, true, true);
            Expression<?> cdt = HibernateUtils.createExtraOrderByFieldsStruct(cbuilder, table);
            String alias = "GROUP_BY" + NUL + cteGroupBy.size() + NUL + table.getJavaType().getSimpleName();
            alias = quote.apply(alias);

            cteGroupBy.add(alias);
            group.add(tid);

            // Include order by fields in the inner select statement
            select.add(tid.alias(alias));
            orderSelections.add(tid);

            // Only add ORDER_BY struct if there are extra order-by fields (avoid empty named_struct)
            if (cdt != null) {
                String extraAlias = "ORDER_BY" + NUL + cteGroupBy.size() + NUL + table.getJavaType().getSimpleName();
                extraAlias = quote.apply(extraAlias);
                select.add(cdt.alias(extraAlias));
                orderSelections.add(cdt);
                extraMappers.add(null);
            }

            extraMappers.add(null);
        });

        cteQuery.multiselect(select);
        cteQuery.groupBy(builder.function("all", Object.class));

        addOrgFilter();

        // Exploded table predicates should be added as OR clause per table
        if (MapUtils.isNotEmpty(explodedTablePredicates)) {
            for (Map.Entry<From<?, ?>, List<Predicate>> entry : explodedTablePredicates.entrySet()) {
                List<Predicate> tablePredicates = entry.getValue();
                if (CollectionUtils.isNotEmpty(tablePredicates)) {
                    Predicate orPredicate = cbuilder.or(tablePredicates.toArray(new Predicate[0]));
                    predicates.add(orPredicate);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(predicates)) {
            Predicate[] arr = predicates.toArray(Predicate[]::new);
            cteQuery.where(arr);
        }
    }

    private Expression<?> shouldMask(ColumnResolver.Resolved r) {
        String rid = r.getCacheKey();
        if (oauth == false || maskingEnabled.getOrDefault(rid, true) == false) {
            return builder.literal(false);
        }

        boolean found = false;
        String maskPermission = null;

        // Find the matching column, and set mask if found
        for (SavedReportView.VisibleColumn c : columns) {
            String cid = c.getColumnId();
            if (rid.equals(cid)) {
                found = true;
                maskPermission = c.getMaskingPermission();
                break;
            }
        }

        if (found == false) {
            // Find the matching filter, and set mask if found
            Iterator<SavedReportView.CriteriaDefinition> it = new SavedReportView.CriteriaDefinition(filters).iterator();
            while (it.hasNext()) {
                SavedReportView.CriteriaDefinition c = it.next();
                String cid = c.getColumnId();
                if (rid.equals(cid)) {
                    found = true;
                    maskPermission = c.getMaskingPermission();
                    break;
                }
            }
        }

        if (found == false) {
            // Find the matching sort, and set mask if found
            for (SavedReportView.SortInfo s : sort) {
                String sid = s.getColumnName();
                if (rid.equals(sid)) {
                    maskPermission = s.getMaskingPermission();
                    found = true;
                    break;
                }
            }
        }

        if (found && maskPermission != null) {
            maskingEnabled.put(rid, true);
            return HibernateUtils.abacShouldMask(builder, r, maskPermission);
        } else {
            maskingEnabled.put(rid, false);
            return builder.literal(false);
        }
    }

    private Expression<?> maskValue(ColumnResolver.Resolved r, Expression<?> value) {
        if (oauth == false) {
            return value;
        }

        boolean found = false;
        String maskPermission = null;
        String rid = r.getColumnID();

        // Find the matching column, and set mask if found
        for (SavedReportView.VisibleColumn c : columns) {
            String cid = c.getColumnId();
            if (rid.equals(cid)) {
                found = true;
                maskPermission = c.getMaskingPermission();
                break;
            }
        }

        if (found == false) {
            // Find the matching filter, and set mask if found
            Iterator<SavedReportView.CriteriaDefinition> it = new SavedReportView.CriteriaDefinition(filters).iterator();
            while (it.hasNext()) {
                SavedReportView.CriteriaDefinition c = it.next();
                String cid = c.getColumnId();
                if (rid.equals(cid)) {
                    found = true;
                    maskPermission = c.getMaskingPermission();
                    break;
                }
            }
        }

        if (found == false) {
            // Find the matching sort, and set mask if found
            for (SavedReportView.SortInfo s : sort) {
                String sid = s.getColumnName();
                if (rid.equals(sid)) {
                    maskPermission = s.getMaskingPermission();
                    found = true;
                    break;
                }
            }
        }

        if (found && maskPermission != null) {
            value = HibernateUtils.abacMask(builder, value, r, maskPermission);
        }

        return value;
    }

    /**
     * Add organization filter to the CTE
     */
    private void addOrgFilter() {
        if (DatabricksSessionContext.isABACEnabled()) {
            // ABAC and RBAC_ABAC handle org filtering using the row filter policy
            return;
        }

        OrgFilterCreator bean;
        OrgFilterReplacement ofr = rootClass.getAnnotation(OrgFilterReplacement.class);
        if (ofr != null) {
            bean = ApplicationContextProvider.getApplicationContext().getBean(ofr.value());
        } else {
            bean = ApplicationContextProvider.getApplicationContext().getBean(DefaultOrgFilterCreator.class);
        }

        Predicate p = bean.createOrgFilter(cteQuery, root, cbuilder);
        if (p != null) {
            predicates.add(p);
        }
    }

    /**
     * Handle sort fields from SortCoalescedBy annotation and add them to the struct map
     *
     * @param resolved  The resolved column information
     * @param structMap The map to add sort fields to
     */
    private void handleSortCoalescedBy(ColumnResolver.Resolved resolved, LinkedHashMap<String, Expression<Object>> structMap) {
        Optional.ofNullable(resolved.getTables().peek().getJavaType().getAnnotation(SortCoalescedBy.class))
            .ifPresent(annotationObj -> {
                String[] sortFields = annotationObj.value();
                if (sortFields != null && sortFields.length > 0) {
                    LinkedHashMap<String, Expression<Object>> sortMap = new LinkedHashMap<>();
                    Arrays.stream(sortFields)
                        .forEach(f -> {
                            try {
                                Expression<Object> expression = Optional.ofNullable(resolved.getTables().peek())
                                    .map(table -> table.get(f))
                                    .orElseThrow(() -> new IllegalStateException(
                                        "Failed to resolve sort field: " + f));
                                sortMap.put(f, expression);
                            } catch (Exception e) {
                                log.error("Error resolving sort expression for field: {}", f, e);
                            }
                        });
                    @SuppressWarnings("unchecked")
                    Expression<Object> sortStruct = (Expression<Object>) HibernateUtils.createStruct(cbuilder, sortMap, false);
                    structMap.put("sort", sortStruct);
                }
            });
    }

    private void cteSelect(SavedReportView.VisibleColumn column) {
        List<SavedReportView.VisibleColumn> merged = Optional.ofNullable(column.getMergedColumnMetaData())
            .map(SavedReportView.MergedColumnMetaData::getSourceColumns)
            .orElse(List.of());

        // If this is a merged column, recurse
        if (merged.isEmpty() == false) {
            merged.forEach(this::cteSelect);
            return;
        }

        // Ignore invisible columns
        if (column.isVisible() == false) {
            return;
        }

        String columnID = column.getColumnId();

        // Attempt to resolve the column, if it fails we will instead select NULL in the outer query.
        ColumnResolver.Resolved resolved;
        try {
            resolved = cteSelect(columnID, column.getEntityType(), column.isExploded());
        } catch (ColumnResolver.NoSuchColumnException e) {
            log.error("Failed to resolve column {} in {}", column, rootClass, e);
            return;
        }

        // Determine if fields from the table the column is in should be coalesced or exploded
        From<?, ?> table = resolved.getTables().peek();
        Boolean coalesce = coalescedTables.get(table);
        if (coalesce == null) {
            populateCoalesce(table, resolved, column.isExploded());
        }

        if (column.getDataType() == ReportAttributeDataType.HYPERLINK) {
            // Select hyperlink data, if any
            Field field = resolved.getFields().peek();
            Hyperlink h = field.getAnnotation(Hyperlink.class);
            if (h != null) {
                selectCTEHyperlink(resolved, h);
                return;
            }

            AdvancedHyperlink ah = field.getAnnotation(AdvancedHyperlink.class);
            if (ah != null) {
                selectCTEHyperlink(resolved, ah);
                return;
            }

            Class<?> type = table.getJavaType();
            ah = type.getAnnotation(AdvancedHyperlink.class);
            if (ah != null) {
                selectCTEHyperlink(resolved, ah);
                return;
            }

            log.warn("No hyperlink information for {}, {}", columnID, field);
        }
    }

    private void selectCTEHyperlink(ColumnResolver.Resolved resolved, Hyperlink hyperlink) {
        HyperlinkInfo info = selectCTEHyperlink(resolved, hyperlink, null, List.of(hyperlink.field()));
        log.debug("{} hyperlink info {}", resolved.getColumnID(), info);
    }

    private void selectCTEHyperlink(ColumnResolver.Resolved resolved, AdvancedHyperlink hyperlink) {
        HyperlinkInfo info = selectCTEHyperlink(resolved, null, hyperlink, Arrays.asList(hyperlink.fields()));
        log.debug("{} advanced  hyperlink info {}", resolved.getColumnID(), info);
    }

    /**
     * Select data for hyperlinks in the CTE.
     *
     * @param resolved
     * @param hyperlink
     * @param advancedHyperlink
     * @param fields
     * @return
     */
    private HyperlinkInfo selectCTEHyperlink(
        ColumnResolver.Resolved resolved,
        Hyperlink hyperlink,
        AdvancedHyperlink advancedHyperlink,
        List<String> fields
    ) {
        String columnID = resolved.getCacheKey();
        HyperlinkInfo info = hyperlinkInfo.get(columnID);
        if (info != null) {
            return info;
        }

        Map<String, Boolean> structFields = new TreeMap<>();
        Map<String, Expression<?>> values = new TreeMap<>();
        List<Class<?>> types = new ArrayList<>(fields.size());
        for (String field : fields) {
            From<?, ?> table = resolved.getTables().peek();
            Path<Object> p = table.get(field);
            types.add(p.getJavaType());

            log.debug("Selecting hyperlink data for {}, {} from {}", columnID, field, table.getJavaType());
            Expression<?> e = HibernateUtils.optionallyCreateStruct(cbuilder, p);

            structFields.put(field, e instanceof Path == false);

            if (resolved.isPivot()) {
                e = HibernateUtils.caseWhen(cbuilder, e, resolved.getPredicates());
            }

            values.put(field, e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Expression<Object>> _values = (Map) values;
        info = new HyperlinkInfo(
            columnID,
            hyperlink,
            advancedHyperlink,
            fields,
            types,
            HibernateUtils.createStruct(cbuilder, _values, true),
            structFields
        );

        hyperlinkInfo.put(columnID, info);

        String remapped = remappedColumnIDs.get(columnID);
        if (remapped != null) {
            hyperlinkInfo.put(remapped, info);
        }

        return info;
    }

    /**
     * Select data for filters in the CTE.
     *
     * @param filters
     */
    private void cteSelect(Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> filters) {
        filters.values().stream().flatMap(List::stream).forEach(this::cteSelect);
    }

    /**
     * Select data for filter(s) in the CTE.
     * <p>
     * If this is a nested filter, it will recurse into {@link #cteSelect(java.util.Map)}.
     *
     * @param criteria
     */
    private void cteSelect(SavedReportView.CriteriaDefinition criteria) {
        Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> nested = criteria.getFilterCriteria();
        if (MapUtils.isNotEmpty(nested)) {
            cteSelect(nested);
            return;
        }

        String columnID = criteria.getColumnId();

        try {
            cteSelect(columnID, criteria.getEntityType(), null);
        } catch (ColumnResolver.NoSuchColumnException e) {
            // Unlike for columns, if we can't resolve a filter we should error out.
            throw new IllegalStateException("Failed to resolve field for filter " + columnID + " in " + rootClass, e);
        }
    }

    /**
     * Resolve a columnID for the CTE.
     * <p>
     * This will dispatch to the appropriate method based on the type.
     *
     * @param columnID
     * @param type
     * @return
     * @throws com.onetrust.otinsightscommand.report.builder.ColumnResolver.NoSuchColumnException
     */
    private ColumnResolver.Resolved cteSelect(
        String columnID,
        ReportAttributeEntityType type,
        Boolean isExploded
    ) throws ColumnResolver.NoSuchColumnException {
        ColumnResolver.Resolved resolved;
        switch (type) {
            case ATTRIBUTE:
            case ATTRIBUTE_ARRAY:
            case DEFAULT:
            case DEFAULT_ARRAY:
            case DYNAMIC_ATTRIBUES_MAP:
            case QUESTIONS:
                resolved = resolver.resolveColumn(columnID);
                break;
            case DYNAMIC_ATTRIBUES:
                resolved = cteSelectDynamicAttributes(columnID);
                break;
            case RELATED_OBJECT_DYANMIC_ATTRIBUTES:
                resolved = cteSelectRelatedObjectDynamicAttributes(columnID, isExploded);
                break;
            default:
                throw new UnsupportedOperationException("Can not resolve column " + columnID + " of type " + type);
        }

        return resolved;
    }

    /**
     * Add a table to {@link #coalescedTables}.
     *
     * @param table
     * @param resolved
     * @param exploded
     */
    private void populateCoalesce(From<?, ?> table, ColumnResolver.Resolved resolved, boolean exploded) {
        if (coalescedTables.containsKey(table)) {
            return;
        }

        int xToManyJoins = 0;

        Iterator<From<?, ?>> it = resolved.getTables().descendingIterator();
        while (it.hasNext()) {
            From<?, ?> t = it.next();
            if (t instanceof Join<?, ?> j) {
                Attribute.PersistentAttributeType type = j.getAttribute().getPersistentAttributeType();
                switch (type) {
                    case ONE_TO_MANY:
                    case MANY_TO_MANY:
                        xToManyJoins++;
                        break;
                    default:
                        break;
                }
            }

            if (exploded) {
                coalescedTables.put(t, xToManyJoins > 1);
            } else {
                coalescedTables.putIfAbsent(t, true);
            }
        }
    }

    /**
     * Check if a column should be coalesced or not.
     *
     * @param resolved
     * @return
     */
    public boolean shouldCoalesce(ColumnResolver.Resolved resolved) {
        if (resolved.isArray()) {
            return false;
        }

        From<?, ?> table = resolved.getTables().peek();
        if (coalescedTables.getOrDefault(table, true)) {
            return true;
        }

        Coalesce coalesce = resolved.getFields().peek().getAnnotation(Coalesce.class);
        if (coalesce == null) {
            return false;
        }

        boolean exploded = columns.stream()
            .filter(c -> c.getColumnId().equals(resolved.getColumnID()))
            .anyMatch(SavedReportView.VisibleColumn::isExploded);

        return exploded == false;
    }

    /**
     * Resolve a DYNAMIC_ATTRIBUES column.
     *
     * @param columnID
     * @return
     * @throws com.onetrust.otinsightscommand.report.builder.ColumnResolver.NoSuchColumnException
     */
    private ColumnResolver.Resolved cteSelectDynamicAttributes(String columnID) throws ColumnResolver.NoSuchColumnException {
        log.trace("Resolving dynamic attribute column {}", columnID);

        Field field = FieldCache.get(rootClass).getDynamicAttributesField();
        if (field == null) {
            throw new ColumnResolver.NoSuchColumnException(
                columnID,
                rootClass + " does not have any field with @DynamicAttributes, can not resolve " + columnID
            );
        }

        String resolveID = field.getName() + NUL + columnID;
        return resolver.resolveColumn(columnID, resolveID, null, "" + NUL, null);
    }

    /**
     * Resolve a RELATED_OBJECT_DYANMIC_ATTRIBUTES column.
     * <p>
     * Note that the columnID will have already been modified by {@link RelatedObjectDynamicAttributeRemapper}.
     *
     * @param columnID
     * @return
     * @throws com.onetrust.otinsightscommand.report.builder.ColumnResolver.NoSuchColumnException
     */
    private ColumnResolver.Resolved cteSelectRelatedObjectDynamicAttributes(
        String columnID,
        Boolean isExplodedColumn
    ) throws ColumnResolver.NoSuchColumnException {
        log.trace("Resolving related object dynamic attribute column {}", columnID);

        String[] split = columnID.split("" + NUL);
        final String pathMapper = split[0];
        final String linkType = split[1];
        final String attributeID = split[2];
        final String relatedObjectType = split[3];

        String resolveID = (pathMapper.isEmpty() ? "relatedAttributes" : pathMapper) + NUL + attributeID;

        Consumer<ColumnResolver.Context> modify = ctx -> {
            boolean isExploded = columns.stream().anyMatch(column -> column.getColumnId().equals(columnID) && column.isExploded());
            if (ctx.usedCustomResolver) {
                if (isExploded) {
                    explodedTablePredicates.computeIfAbsent(ctx.tables.peek(), k -> new LinkedList<>()).addAll(ctx.predicates);
                }

                return;
            }

            From<?, ?> table = ctx.tables.peek();
            Class<?> type = table.getJavaType();
            FieldCache cache = FieldCache.get(type);
            Field linkTypeField = cache.getField("linkType");
            Field relatedObjectTypeField = cache.getField("relatedObjectType");

            log.trace("Adding predicate {} = {}", relatedObjectTypeField, relatedObjectType);
            Predicate p = builder.ilike(table.get(relatedObjectTypeField.getName()), relatedObjectType);
            ctx.predicates.add(p);
            if (isExploded) {
                predicates.add(p);
            }

            if (linkType.isEmpty() == false) {
                log.trace("Adding predicate {} = {}", linkTypeField, linkType);
                p = builder.equal(table.get(linkTypeField.getName()), linkType);
                ctx.predicates.add(p);
            }
        };

        return resolver.resolveColumn(columnID, resolveID, modify, "" + NUL, isExplodedColumn);
    }

    /**
     * Build the outer query.
     */
    private void buildQuery() {
        columns.forEach(this::select);
        where.putAll(convertFilters(filters));

        for (SavedReportView.SortInfo s : sort) {
            ColumnResolver.Resolved resolved = resolver.getResolvedColumn(s.getColumnName());
            Expression<?> f = queryRoot.get(resolved.getAlias());
            if (shouldCoalesce(resolved) || resolved.isPivot()) {
                f = HibernateUtils.transformExtract(cbuilder, f, "value");
            } else {
                f = HibernateUtils.fieldAt(cbuilder, f, "value");
                groupBy.add(f);
            }

            sortSelections.add(f);
        }
    }

    /**
     * Select columns from the CTE in the outer query.
     *
     * @param column
     */
    private void select(SavedReportView.VisibleColumn column) {
        List<SavedReportView.VisibleColumn> merged = Optional.ofNullable(column.getMergedColumnMetaData())
            .map(SavedReportView.MergedColumnMetaData::getSourceColumns)
            .orElse(List.of());

        // If this is a merged column, recurse
        if (merged.isEmpty() == false) {
            merged.forEach(this::select);
            return;
        }

        String columnID = column.getColumnId();
        ColumnResolver.Resolved resolved = resolver.getResolvedColumn(columnID);
        if (resolved == null) {
            // Resolving the column failed in the CTE, so select NULL instead.
            selections.add(HibernateUtils.withCommentPrefix(cbuilder, cbuilder.function("null", Object.class), columnID));
            selectMappers.add(null);
            return;
        }

        Expression<?> e;
        boolean coalesce;
        ColumnMappers.ExpressionMapper exprMapper = null;
        ColumnMappers.Mapper mapper = null;

        e = queryRoot.get(resolved.getAlias());
        coalesce = shouldCoalesce(resolved) || resolved.isPivot();
        if (coalesce) {
            Aggregate.Function function = Optional.ofNullable(resolved.getFields().peek().getAnnotation(Aggregate.class))
                .map(Aggregate::value)
                .orElse(Aggregate.Function.ARRAY);

            switch (function) {
                case ARRAY:
                    e = HibernateUtils.transformExtract(cbuilder, e, "value");

                    exprMapper = ColumnMappers::toJSON;
                    mapper = ColumnMappers::mapArray;
                    break;
                default:
                    break;
            }
        } else {
            e = HibernateUtils.fieldAt(cbuilder, e, "value");
        }

        Class<?> type = resolved.getExpressionType();
        if (mapper == null) {
            if (resolved.isArray()) {
                mapper = ColumnMappers::mapArray;
                exprMapper = ColumnMappers::toJSON;
            } else if (resolved.isStruct()) {
                mapper = ColumnMappers::mapJSON;
                exprMapper = ColumnMappers::toJSON;
            } else if (HibernateUtils.isSimpleSQLType(type)) {
                e = HibernateUtils.hibernateCast(e, resolved.getFields().peek());
            }
        }

        selections.add(e);
        selectedColumns.add(columnID);
        selectMappers.add(new MapperInfo(exprMapper, mapper, resolved.getExpressionType()));

        if (column.getDataType() == ReportAttributeDataType.HYPERLINK) {
            selectHyperlink(resolved, coalesce);
        }

        selectShouldMask(resolved, coalesce);
    }

    /**
     * Select hyperlink data from the CTE in the outer query.
     *
     * @param resolved
     * @param coalesce
     */
    private void selectHyperlink(ColumnResolver.Resolved resolved, boolean coalesce) {
        String columnID = resolved.getCacheKey();
        HyperlinkInfo info = hyperlinkInfo.get(columnID);
        if (info == null) {
            log.warn("No hyperlink info for {}", columnID);
            return;
        }

        int index = hyperlinkSelections.size();
        hyperlinkIndexes.put(columnID, index);

        String remap = remappedColumnIDs.get(columnID);
        if (remap != null) {
            hyperlinkIndexes.put(remap, index);
        }

        final Expression<?> e = queryRoot.get(resolved.getAlias());

        Iterator<Class<?>> types = info.types.iterator();
        for (String field : info.fields) {
            Class<?> type = types.next();

            if (coalesce) {
                Expression<?> t = HibernateUtils.transformExtract(cbuilder, e, "hyperlink", field);

                hyperlinkSelections.add(t);
                hyperlinkMappers.add(new MapperInfo(ColumnMappers::toJSON, ColumnMappers::mapArray, type));
            } else {
                Expression<?> f = HibernateUtils.fieldAt(cbuilder, e, "hyperlink", field);

                ColumnMappers.ExpressionMapper exprMapper = null;
                if (info.structFields.getOrDefault(field, false)) {
                    exprMapper = ColumnMappers::toJSON;
                }

                hyperlinkSelections.add(f);
                hyperlinkMappers.add(new MapperInfo(exprMapper, null, type));
            }
        }
    }

    private void selectShouldMask(ColumnResolver.Resolved resolved, boolean coalesce) {
        String rid = resolved.getCacheKey();
        if (maskingEnabled.get(rid) == false) {
            return;
        }

        int index = maskingSelections.size();
        maskingIndexes.put(rid, index);

        String remap = remappedColumnIDs.get(rid);
        if (remap != null) {
            maskingIndexes.put(remap, index);
        }

        Expression<?> e = queryRoot.get(resolved.getAlias());
        if (coalesce) {
            Expression<?> t = HibernateUtils.transformExtract(cbuilder, e, "masked");
            maskingSelections.add(t);
            maskingMappers.add(new MapperInfo(ColumnMappers::toJSON, ColumnMappers::mapArray, boolean.class));
        } else {
            Expression<?> f = HibernateUtils.fieldAt(cbuilder, e, "masked");
            maskingSelections.add(f);
            maskingMappers.add(new MapperInfo(null, ColumnMappers::mapBoolean, boolean.class));
        }
    }

    /**
     * Convert the converted filters into a single {@link Predicate}.
     *
     * @param predicates
     * @return
     */
    private Predicate toPredicate(Map<FilterLogicalOperator, List<Predicate>> predicates) {
        Predicate p = null;
        for (Map.Entry<FilterLogicalOperator, List<Predicate>> entry : predicates.entrySet()) {
            FilterLogicalOperator key = entry.getKey();
            List<Predicate> value = entry.getValue();
            if (CollectionUtils.isEmpty(value)) {
                continue;
            }

            Predicate[] arr = value.toArray(Predicate[]::new);

            Predicate cmb = switch (key) {
                case AND -> builder.and(arr);
                case OR -> builder.or(arr);
            };

            if (p == null) {
                p = cmb;
            } else {
                p = builder.and(p, cmb);
            }
        }

        return p;
    }

    /**
     * Convert filters to {@link Predicate}s.
     * <p>
     * This will call
     * {@link #convertFilter(com.onetrust.otinsightscommand.dto.savedview.SavedReportView.CriteriaDefinition)}.
     *
     * @param filters
     * @return
     */
    private Map<FilterLogicalOperator, List<Predicate>> convertFilters(Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> filters) {
        Map<FilterLogicalOperator, List<Predicate>> converted = new EnumMap<>(FilterLogicalOperator.class);
        for (Map.Entry<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> entry : filters.entrySet()) {
            FilterLogicalOperator key = entry.getKey();
            List<SavedReportView.CriteriaDefinition> value = entry.getValue();
            if (CollectionUtils.isEmpty(value)) {
                continue;
            }

            List<Predicate> predicates = new ArrayList<>(value.size());
            value.stream().map(this::convertFilter).forEach(predicates::add);
            converted.put(key, predicates);
        }

        return converted;
    }

    /**
     * Convert a {@link SavedReportView.CriteriaDefinition} to a {@link Predicate}.
     * <p>
     * If the filter is nested, it will recurse into {@link #convertFilters(java.util.Map)}.
     *
     * @param criteria
     * @return
     */
    private Predicate convertFilter(SavedReportView.CriteriaDefinition criteria) {
        Map<FilterLogicalOperator, List<SavedReportView.CriteriaDefinition>> nested = criteria.getFilterCriteria();
        if (MapUtils.isNotEmpty(nested)) {
            return toPredicate(convertFilters(nested));
        }

        String columnID = criteria.getColumnId();
        ColumnResolver.Resolved resolved = resolver.getResolvedColumn(columnID);
        if (resolved == null) {
            throw new IllegalStateException(columnID + " was not resolved in the CTE, can not create a filter");
        }

        boolean coalesce = shouldCoalesce(resolved) || resolved.isPivot();
        JpaPath<Object> path = queryRoot.get(resolved.getAlias());

        if (coalesce == false) {
            groupBy.add(path);
        }

        Class<?> type = resolved.getExpressionType();

        Expression<?> expr = path;

        // TODO: Figure out a better way to do this
        boolean unwrap = true;
        boolean array = coalesce;
        if (isEligibleTypeForExtract(type)) {
            array = true;
            unwrap = false;

            if (coalesce) {
                expr = builder.function(
                    "flatten",
                    String.class,
                    HibernateUtils.transformExtract(builder, expr, "value")
                );
            } else {
                expr = HibernateUtils.fieldAt(builder, expr, "value");
                if (AssessmentQuestionRoot.class.isAssignableFrom(type)) {
                    expr = HibernateUtils.fieldAt(builder, expr, "responses");
                }
            }

            type = com.onetrust.otinsightscommand.report.Attribute.class;
        }

        return PredicateBuilder.toPredicate(cbuilder, expr, type, criteria, array, unwrap, PredicateBuilder.Type.COLUMN_REPORT, resolved.isArray());
    }

    @Override
    public TypedQuery<Object[]> build() {
        Predicate p = toPredicate(where);
        if (p != null) {
            query.where(p);
        }

        p = toPredicate(having);
        if (p != null) {
            query.having(p);
        }

        Iterator<MapperInfo> mit = mappers.iterator();
        Iterator<Expression<?>> sit = select.iterator();
        List<Selection<?>> l = new ArrayList<>(select.size());
        while (sit.hasNext()) {
            MapperInfo m = mit.next();
            Expression<?> e = sit.next();
            if (m != null && m.exprMapper != null) {
                e = m.exprMapper.apply(cbuilder, e);
            }

            l.add(e.alias("_" + l.size()));
        }

        List<Order> orderBy = new ArrayList<>(sort.size());

        Iterator<SavedReportView.SortInfo> i1 = sort.iterator();
        Iterator<Expression<?>> i2 = sortSelections.iterator();
        while (i1.hasNext()) {
            boolean ascending = i1.next().isAscending();
            Expression<?> e = i2.next();

            if (ascending) {
                orderBy.add(cbuilder.asc(e));
            } else {
                orderBy.add(cbuilder.desc(e));
            }
        }

        for (Expression<?> e : orderSelections) {
            Expression<?> order = queryRoot.get(e.getAlias());
            orderBy.add(cbuilder.asc(order));

            // Include order by fields in the outer select statement
            l.add(order.alias("_" + l.size()));
        }

        query.multiselect(l);
        query.distinct(distinct);

        query.orderBy(orderBy);

        return manager.createQuery(query);
    }

    /**
     * Get the total number of records the query would return.
     *
     * @return
     */
    @Override
    public long count() {
        // We need to get the regular query so that we can extract its AST.
        TypedQuery<Object[]> tq = build();

        @SuppressWarnings("unchecked")
        SqmSelectStatement<Object[]> statement = tq.unwrap(SqmSelectStatement.class);

        NodeBuilder nb = statement.nodeBuilder();

        SqmQuerySpec<Long> part = new SqmQuerySpec<>(nb);
        part.setSelectClause(new SqmSelectClause(false, nb));
        part.setFromClause(new SqmFromClause());

        Map<String, SqmCteStatement<?>> cteStatements = new TreeMap<>();

        SqmSelectStatement<Long> countSelect = new SqmSelectStatement<>(part, Long.class, cteStatements, SqmQuerySource.CRITERIA, nb);
        SqmCteStatement<?> data = new SqmCteStatement<>("data", statement, countSelect, nb);

        cteStatements.put("data", data);
        countSelect.select(nb.count());
        countSelect.from(data);

        return manager.createQuery(countSelect).getSingleResult();
    }

    /**
     * Map a raw row from the query using the mappers.
     *
     * @param row
     */
    public void mapRow(Object[] row) {
        int length = row.length;
        if (length != mappers.size()) {
            throw new IllegalArgumentException("row has " + length + " columns, but this QueryBuilder has " + mappers.size());
        }

        for (int i = 0; i < length; i++) {
            MapperInfo mapper = mappers.get(i);
            if (mapper == null || mapper.mapper == null) {
                continue;
            }

            row[i] = mapper.mapper.apply(row[i], mapper.type);
        }

        int i = -1;
        for (String id : selectedColumns) {
            i++;
            int mi = getMaskingIndex(id);
            if (mi < 0) {
                continue;
            }

            if (row[mi] instanceof Boolean masked && masked) {
                row[i] = "****";
            } else if (row[mi] instanceof List l) {
                maskList(row, i, l);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void maskList(Object[] row, int i, List masking) {
        List<Boolean> mask = masking;
        if (row[i] instanceof List values) {
            if (values.size() != mask.size()) {
                throw new IllegalStateException("row[" + i + "] has size " + values.size() + " instead of " + mask.size());
            }

            for (int o = 0; o < values.size(); o++) {
                Boolean m = mask.get(o);
                if (m != null && m) {
                    values.set(o, "****");
                }
            }
        } else if (row[i] != null) {
            throw new IllegalStateException("row[" + i + "] is not a List but is a " + row[i].getClass() + " " + row[i]);
        }
    }

    /**
     * Get the index of the hyperlink info in the result array.
     *
     * @param columnID
     * @return
     */
    @Override
    public int getHyperlinkIndex(String columnID) {
        Integer index = hyperlinkIndexes.get(columnID);
        if (index == null) {
            return -1;
        }

        return selections.size() + index;
    }

    /**
     * Get the index of the masking info in the result array.
     *
     * @param columnID
     * @return
     */
    public int getMaskingIndex(String columnID) {
        Integer index = maskingIndexes.get(columnID);
        if (index == null) {
            return -1;
        } else {
            return selections.size() + hyperlinkSelections.size() + index;
        }
    }
}
