-- sql_onetrust/07_oauth_wiring.sql   (RUN AS OWNER, after 01-06 have already run)
-- Wires a REAL OAuth custom-claim identity flow onto the OneTrust deployment, mirroring
-- the pattern already proven in docs/deployment/oauth-jdbc-flow.md for abac_tpcds. Adds
-- NEW objects rather than mutating the existing test path:
--   - get_user_context()            : live, reads current_oauth_custom_identity_claim()
--   - abac_row_filter_wrapper_oauth : the policy target, calls get_user_context()
-- get_test_user_context() / abac_row_filter_wrapper are left untouched -- Step 6's
-- owner-side sanity checks (06_test_cases.sql) still call the ORIGINAL wrapper and would
-- hard-error under OAuth (current_oauth_custom_identity_claim() throws with no claim
-- present, i.e. when queried as yourself with a normal login, not a claim-carrying token).
--
-- Replace <SERVICE_PRINCIPAL> below with the real service principal application id
-- before running (same value used in the Phase 1 notebook's service_principal widget).

-- ---------------------------------------------------------------------
-- get_user_context() : live identity, from the OAuth custom claim.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'Live ABAC context from the OAuth custom_claim -- see docs/deployment/oauth-jdbc-flow.md'
RETURN from_json(
  current_oauth_custom_identity_claim(),
  'STRUCT<tenant:int, user:string, org:string, mode:string, root:string, permissions:array<string>>'
);

-- ---------------------------------------------------------------------
-- abac_row_filter_wrapper_oauth : the function the 4 policies will be repointed to.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_onetrust.onetrust_sim.abac_row_filter(
  entity_id, abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type), org_id,
  abac_onetrust.onetrust_sim.get_user_context()
);

-- ---------------------------------------------------------------------
-- Repoint the 4 policies from abac_row_filter_wrapper -> abac_row_filter_wrapper_oauth.
-- Identical MATCH COLUMNS / USING COLUMNS / TO to sql_onetrust/04_policies.sql.
-- ---------------------------------------------------------------------
CREATE OR REPLACE POLICY onetrust_sim_cmb_assessment_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_assessment
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'ASSESSMENT', '100');

CREATE OR REPLACE POLICY onetrust_sim_cmb_controlimplementation_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_controlimplementation
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'CONTROL', '100');

CREATE OR REPLACE POLICY onetrust_sim_cmb_template_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_template
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'TEMPLATE', '100');

CREATE OR REPLACE POLICY onetrust_sim_cmb_v_inventoryaggregatedrisksummary_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type, has_tag('abac_column_org') as org
USING COLUMNS (id, type, org);

-- ---------------------------------------------------------------------
-- Grants: let the SP reach data + functions. Row-filter functions read referenced
-- metadata tables with the FUNCTION OWNER's privileges (definer rights), so the SP
-- usually does not need SELECT on ABAC_EntitySubjectAssignment etc. -- see the
-- commented fallback block if your workspace does not use definer rights.
-- ---------------------------------------------------------------------
GRANT USE CATALOG ON CATALOG abac_onetrust                      TO `<SERVICE_PRINCIPAL>`;
GRANT USE SCHEMA  ON SCHEMA  abac_onetrust.onetrust_sim          TO `<SERVICE_PRINCIPAL>`;

GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.cmb_assessment                       TO `<SERVICE_PRINCIPAL>`;
GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.cmb_controlimplementation            TO `<SERVICE_PRINCIPAL>`;
GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.cmb_template                         TO `<SERVICE_PRINCIPAL>`;
GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary TO `<SERVICE_PRINCIPAL>`;

-- OrgHierarchyBase is not a policied table -- it's the self-seeding test fixture's target
-- (Runner.java's onetrustFixtureInserts()/onetrustFixtureDeletes(), see runOnetrustCases()).
-- The fixture INSERTs are self-referential (INSERT INTO ... SELECT ... FROM OrgHierarchyBase
-- WHERE ...) and the DELETE also reads to scope its WHERE clause, so the SP needs BOTH SELECT
-- and MODIFY here, not just one.
GRANT SELECT, MODIFY ON TABLE abac_onetrust.onetrust_sim.OrgHierarchyBase TO `<SERVICE_PRINCIPAL>`;

GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth TO `<SERVICE_PRINCIPAL>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.abac_row_filter              TO `<SERVICE_PRINCIPAL>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.get_user_context             TO `<SERVICE_PRINCIPAL>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.entity_type_to_object_type   TO `<SERVICE_PRINCIPAL>`;

-- Fallback ONLY if metadata reads are not covered by definer rights (symptom: queries
-- succeed with 0 rows even for an assignment you know exists):
-- GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment TO `<SERVICE_PRINCIPAL>`;
-- GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.ABAC_Assignment             TO `<SERVICE_PRINCIPAL>`;
-- GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.UserGroupMembers            TO `<SERVICE_PRINCIPAL>`;
-- GRANT SELECT ON TABLE abac_onetrust.onetrust_sim.ABAC_OrgHierarchy           TO `<SERVICE_PRINCIPAL>`;

-- Expected: no error. SHOW POLICIES ON TABLE abac_onetrust.onetrust_sim.cmb_assessment
-- should now show onetrust_sim_cmb_assessment_abac_policy with row_filter
-- abac_row_filter_wrapper_oauth (not abac_row_filter_wrapper).
