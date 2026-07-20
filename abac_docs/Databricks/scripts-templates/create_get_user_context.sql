CREATE OR REPLACE FUNCTION `ABAC`.`get_user_context`()
RETURNS STRUCT<tenant: int, user: string, org: string, mode: string, root: string, permissions: array<string>>
COMMENT 'Convert the oauth custom_claim into a struct and return it'
RETURN from_json(current_oauth_custom_identity_claim(), 'STRUCT<tenant: int, user: string, org: string, mode: string, root: string, permissions: array<string>>')
