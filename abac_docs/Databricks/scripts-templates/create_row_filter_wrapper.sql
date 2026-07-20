CREATE OR REPLACE FUNCTION `@DBNAME`.`abac_row_filter_wrapper`(entity_id string, object_type string, org_id string)
RETURNS BOOLEAN
RETURN `@DBNAME`.abac_row_filter(entity_id, abac.entity_type_to_object_type(object_type), org_id, abac.get_user_context())
