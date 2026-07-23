-- sql_onetrust/04_policies.sql   (requires ABAC enabled + tags from 02_tags.sql)
-- Same pattern as sql/08_policies_row_filter.sql. TO clause: replace
-- `<SERVICE_PRINCIPAL>` with the real service principal application id before running
-- (see docs/deployment/runbook.md for how the TPC-DS POC resolved this).

CREATE OR REPLACE POLICY onetrust_sim_cmb_assessment_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_assessment
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'ASSESSMENT', '100');

CREATE OR REPLACE POLICY onetrust_sim_cmb_controlimplementation_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_controlimplementation
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'CONTROL', '100');

CREATE OR REPLACE POLICY onetrust_sim_cmb_template_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_template
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'TEMPLATE', '100');

-- cmb_v_inventoryaggregatedrisksummary: real per-row type + org columns — the
-- default/tagged-type shape (3 tags), not a literal.
CREATE OR REPLACE POLICY onetrust_sim_cmb_v_inventoryaggregatedrisksummary_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type, has_tag('abac_column_org') as org
USING COLUMNS (id, type, org);

SHOW POLICIES ON SCHEMA abac_onetrust.onetrust_sim;
