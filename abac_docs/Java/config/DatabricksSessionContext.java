package com.onetrust.otinsightscommand.databrickssql.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.onetrust.assertions.operators.BooleanOperator;
import com.onetrust.assertions.service.AssertionService;
import com.onetrust.framework.security.context.MsSecurityContext;
import com.onetrust.framework.security.data.UserDetails;
import com.onetrust.otinsightscommand.config.ApplicationContextProvider;
import com.onetrust.otinsightscommand.constant.ObjectType;
import com.onetrust.otinsightscommand.dto.savedview.SavedReportView;
import com.onetrust.otinsightscommand.util.SafeAutoCloseable;
import com.onetrust.reporting.enums.ReportEntityType;
import com.onetrust.reporting.enums.ReportableEntityType;
import com.onetrust.reporting.extensions.ReportObjectMapperProxy;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.lang.StringUtils;

@Data
@AllArgsConstructor
@Setter(AccessLevel.NONE)
public class DatabricksSessionContext {
    private static final ThreadLocal<DatabricksSessionContext> CONTEXT = new ThreadLocal<>();

    public static enum Mode {
        DISABLED,
        ABAC,
        RBAC_ABAC
    }

    static DatabricksSessionContext get() {
        return CONTEXT.get();
    }

    public static SafeAutoCloseable setOrg(UUID org) {
        return setOrg(org.toString());
    }

    public static SafeAutoCloseable setOrg(String org) {
        MsSecurityContext.getCurrentUserDetails().setOrgGroupId(org);
        CONTEXT.set(ensure().org(org));
        return DatabricksSessionContext::remove;
    }

    public static Mode getCurrentMode() {
        return Optional.ofNullable(get()).map(DatabricksSessionContext::getMode).orElse(null);
    }

    public static boolean isABACEnabled() {
        Mode m = getCurrentMode();
        return m == Mode.ABAC || m == Mode.RBAC_ABAC;
    }

    public static ObjectType getCurrentObjectType() {
        return Optional.ofNullable(get()).map(DatabricksSessionContext::getObjectType).orElse(null);
    }

    public static void remove() {
        CONTEXT.remove();
    }

    public static DatabricksSessionContext ensure() {
        if (get() == null && MsSecurityContext.getCurrentUserDetails() != null) {
            set();
        }

        return get();
    }

    public static SafeAutoCloseable set() {
        return set((String) null);
    }

    public static SafeAutoCloseable set(ObjectType t) {
        return set(t.objectType);
    }

    public static SafeAutoCloseable set(ReportEntityType type, String subtype) {
        ObjectType t = ObjectType.byTypeAndSubtype(type, subtype);
        return set(t == null ? null : t.objectType);
    }

    public static SafeAutoCloseable set(SavedReportView report) {
        ObjectType t = ObjectType.byReport(report);
        return set(t == null ? null : t.objectType);
    }

    public static SafeAutoCloseable set(ReportableEntityType type) {
        ObjectType t = ObjectType.byReportableEntityType(type);
        return set(t == null ? null : t.objectType);
    }

    /**
     * Set by the report, if not null.
     * Otherwise set by the type.
     *
     * @param report
     * @param type
     * @return
     */
    public static SafeAutoCloseable set(SavedReportView report, ReportableEntityType type) {
        if (report != null) {
            return set(report);
        } else {
            return set(type);
        }
    }

    public static SafeAutoCloseable set(String root) {
        UserDetails details = MsSecurityContext.getCurrentUserDetails();
        if (details == null) {
            remove();
            throw new IllegalStateException("User context is not set");
        }

        long tenant = details.getTenantIdAsLong();
        String user = details.getUserId();
        String org = details.getOrgGroupId();

        DatabricksSessionContext context = CONTEXT.get();
        if (context == null || context.matches(tenant, user, org) == false) {
            context = new DatabricksSessionContext(tenant, user, org, root);
        } else {
            context = context.root(root);
        }

        CONTEXT.set(context);

        return DatabricksSessionContext::remove;
    }

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String token;

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Instant expiry;

    @Getter
    private final long tenant;

    @Getter
    @NonNull
    private final String user;

    @Getter
    @NonNull
    private final String org;

    private Mode mode;

    /**
     * Root object type.
     */
    @Getter
    private final String root;

    @Getter
    private final ObjectType objectType;

    /**
     * Set of related object types that we have permission for.
     **/
    private Set<String> permissions;

    public DatabricksSessionContext(long tenant, String user, String org, String root) {
        this(null, null, tenant, user, org, null, root, ObjectType.byType(root), null);
    }

    public DatabricksSessionContext root(String root) {
        if (StringUtils.equals(root, this.root)) {
            return this;
        }

        // TODO: Lookup from cache
        return new DatabricksSessionContext(null, null, tenant, user, org, null, root, ObjectType.byType(root), null);
    }

    public DatabricksSessionContext org(String org) {
        if (StringUtils.equals(org, this.org)) {
            return this;
        }

        // TODO: Lookup from cache
        return new DatabricksSessionContext(null, null, tenant, user, org, null, root, objectType, null);
    }

    public void setToken(String token, Instant expiry) {
        this.token = token;
        this.expiry = expiry;
    }

    public boolean hasToken() {
        return token != null && Instant.now().isBefore(expiry);
    }

    public boolean matches(long tenant, String user, String org) {
        return this.tenant == tenant && StringUtils.equals(this.user, user) && StringUtils.equals(this.org, org);
    }

    public boolean matches(long tenant, String user, String org, String root) {
        return matches(tenant, user, org) && StringUtils.equals(this.root, root);
    }

    public boolean matches(DatabricksSessionContext other) {
        return other != null && matches(other.tenant, other.user, other.org, other.root);
    }

    public String serialize() {
        getPermissions();

        try {
            return ReportObjectMapperProxy.getObjectMapper().writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Mode getMode() {
        if (mode == null && objectType == null) {
            mode = Mode.DISABLED;
        } else if (mode == null) {
            AssertionService service = ApplicationContextProvider.getApplicationContext().getBean(AssertionService.class);
            if (service.hasPermission(objectType.feature) == false) {
                mode = Mode.DISABLED;
            } else if (service.hasPermissions(List.of(objectType.basicPermission, objectType.advancedPermission), BooleanOperator.OR)) {
                mode = Mode.RBAC_ABAC;
            } else {
                mode = Mode.ABAC;
            }
        }

        return mode;
    }

    public Set<String> getPermissions() {
        if (permissions != null || getMode() == Mode.DISABLED) {
            return permissions;
        }

        Set<String> permissions = new TreeSet<>();
        AssertionService service = ApplicationContextProvider.getApplicationContext().getBean(AssertionService.class);
        objectType.getRelatedObjectPermissions().forEach((type, permission) -> {
            if (service.hasPermission(permission)) {
                permissions.add(type);
            }
        });

        this.permissions = Collections.unmodifiableSet(permissions);
        return this.permissions;
    }
}
