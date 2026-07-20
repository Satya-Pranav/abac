-- =====================================================================
-- 02_metadata_tables.sql
-- The 5 ABAC metadata tables (mirror the customer @DBNAME tables) PLUS
-- ABAC_UserContext (our no-OAuth identity source).
-- ALL live in the dataset schema `abac_tpcds.tpcds_1_delta` — exactly like
-- the customer, whose metadata tables live in the app schema, not in `ABAC`.
-- =====================================================================

CREATE TABLE IF NOT EXISTS abac_tpcds.tpcds_1_delta.ABAC_Assignment (
  id        STRING,
  isActive  BOOLEAN,
  isDeleted BOOLEAN
) COMMENT 'Assignment (grant) records';

CREATE TABLE IF NOT EXISTS abac_tpcds.tpcds_1_delta.ABAC_AssignmentPermission (
  assignmentID STRING,
  name         STRING,
  isDeleted    BOOLEAN
) COMMENT 'Permission names per assignment (used by masking only; not by the row filter)';

CREATE TABLE IF NOT EXISTS abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment (
  entityID     STRING,
  objectType   STRING,
  assignmentID STRING,
  subjectType  STRING,   -- 'USER_ID' or 'USER_GROUP'
  subjectID    STRING,   -- USER_ID -> current_user() (email/SP app id); USER_GROUP -> groupID
  isDeleted    BOOLEAN
) COMMENT 'Maps a subject to an entity via an assignment';

CREATE TABLE IF NOT EXISTS abac_tpcds.tpcds_1_delta.UserGroupMembers (
  groupID   STRING,
  memberID  STRING,      -- current_user() of the member
  isDeleted BOOLEAN
) COMMENT 'Group membership';

CREATE TABLE IF NOT EXISTS abac_tpcds.tpcds_1_delta.orgHierarchy (
  orgID       STRING,
  parentOrgID STRING,
  isDeleted   BOOLEAN
) COMMENT 'Org tree (used by RBAC_ABAC mode)';

-- No-OAuth identity source. get_user_context() looks this up by current_user().
-- user_name MUST equal current_user(): an email for a user, the application id for a service principal.
CREATE TABLE IF NOT EXISTS abac_tpcds.tpcds_1_delta.ABAC_UserContext (
  user_name   STRING,
  tenant      INT,
  org         STRING,
  mode        STRING,          -- 'DISABLE' | 'ABAC' | 'RBAC_ABAC'
  root        STRING,          -- fixed root object type for this principal (e.g. 'Customer')
  permissions ARRAY<STRING>,   -- OBJECT TYPES the principal may view on non-root tables
  isDeleted   BOOLEAN
) COMMENT 'Replaces the OAuth custom identity claim in the no-OAuth phase';
