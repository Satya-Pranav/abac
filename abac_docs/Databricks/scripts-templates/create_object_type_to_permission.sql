CREATE OR REPLACE FUNCTION `ABAC`.`object_type_to_permission`(entity_type string)
RETURNS STRING
COMMENT 'Convert a ABAC object type string to a permission infix'
RETURN CASE entity_type
	WHEN 'AISystem' THEN 'ai-systems'
	WHEN 'Assessment' THEN 'assessments'
	WHEN 'Asset' THEN 'assets'
	WHEN 'Contract' THEN 'contracts'
	WHEN 'Control' THEN 'controls'
	WHEN 'Dataset' THEN 'datasets'
	WHEN 'Engagement' THEN 'engagements'
	WHEN 'Entity' THEN 'entities'
	WHEN 'Issue' THEN 'issues'
	WHEN 'ProcessingActivity' THEN 'processing-activities'
	WHEN 'Project' THEN 'projects'
	WHEN 'Risk' THEN 'risks'
	WHEN 'Vendor' THEN 'vendors'
	ELSE entity_type
END
