-- =====================================================================
-- 09_grants.sql   (let the SERVICE PRINCIPAL reach data + functions)
-- Grants go to the SP (76d5804d-…) — the identity the JDBC/curl session authenticates
-- as and that the policies (08) bind TO. (The dummy emails in claim.user are NOT real
-- principals and are never granted anything.)
-- Row-filter functions read the referenced metadata tables with the FUNCTION OWNER's
-- privileges, so the SP usually does NOT need SELECT on those tables. We grant
-- catalog/schema USE, table SELECT, and function EXECUTE. The commented metadata
-- grants are a fallback if your workspace does not use definer rights.
-- =====================================================================

GRANT USE CATALOG ON CATALOG abac_tpcds                       TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT USE SCHEMA  ON SCHEMA  abac_tpcds.tpcds_1_delta          TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT USE SCHEMA  ON SCHEMA  abac_tpcds.abac                   TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- SELECT on the 12 tables
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.customer         TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.customer_address TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.item             TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.store            TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.store_sales      TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.store_returns    TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.catalog_sales    TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.catalog_returns  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.web_sales        TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.web_returns      TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.web_site         TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.warehouse        TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- EXECUTE on the function chain the policy invokes
GRANT EXECUTE ON FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter         TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac.get_user_context                 TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac.entity_type_to_object_type       TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Fallback ONLY if metadata reads are not covered by definer rights:
-- GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.ABAC_UserContext            TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
-- GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
-- GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.ABAC_Assignment             TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
-- GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.UserGroupMembers            TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
-- GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.orgHierarchy                TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- OPTIONAL: only if you want the JDBC test suite (AbacTestSuite) to SELF-SEED its own
-- namespaced fixture (suite_a_* assignments + SUITE_ORG) and drop it afterward. Without
-- these MODIFY grants the suite silently skips seeding and runs against the existing seed.
GRANT MODIFY ON TABLE abac_tpcds.tpcds_1_delta.ABAC_Assignment             TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT MODIFY ON TABLE abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT MODIFY ON TABLE abac_tpcds.tpcds_1_delta.orgHierarchy                TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
