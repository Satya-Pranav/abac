from app.migrations import migrator

import app.common.functions


class V20260710S002_create_ABAC_functions(migrator.SQLOAuthMigration):
    def get_script(self):
        return [
            app.common.functions.getSQL(
                "ABAC",
                "get_user_context",
                None,
                "createSQL",
            ),
            app.common.functions.getSQL(
                "ABAC",
                "entity_type_to_object_type",
                None,
                "createSQL",
            ),
            app.common.functions.getSQL(
                "ABAC",
                "object_type_to_permission",
                None,
                "createSQL",
            ),
        ]
