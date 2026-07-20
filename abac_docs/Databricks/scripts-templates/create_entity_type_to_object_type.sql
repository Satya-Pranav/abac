CREATE OR REPLACE FUNCTION `ABAC`.`entity_type_to_object_type`(entity_type string)
RETURNS string
COMMENT 'Convert a type string to an ABAC object type string'
RETURN CASE entity_type
	-- ms-entity
	WHEN 'aisystems' THEN 'AISystem'
	WHEN 'datasets' THEN 'Dataset'
	WHEN 'models' THEN 'Model'
	WHEN 'policy' THEN 'Policy'
	WHEN 'privacy-notice' THEN 'DigitalPolicy'
	WHEN 'procedure' THEN 'Procedure'
	WHEN 'projects' THEN 'Project'
	WHEN 'standard' THEN 'Standard'
	-- Inventory
	WHEN 'Assets' THEN 'Asset'
	WHEN 'Data Elements' THEN 'Data Elements' -- TODO
	WHEN 'Entities' THEN 'Entity'
	WHEN 'Processing Activities' THEN 'ProcessingActivity'
	WHEN 'Vendors' THEN 'Vendor'
	ELSE entity_type
END
