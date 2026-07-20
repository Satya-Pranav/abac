from app.common import functions
from app.migrations import migrator


class V20260710S001_create_abac_row_filter_functions(migrator.SQLOAuthMigration):
    def get_script(self) -> list[str]:
        return [
            functions.getSQL(
                "ABAC",
                "row_filter",
                self.migrator_args.database,
                "createSQL",
            ),
            functions.getSQL(
                "ABAC",
                "row_filter_wrapper",
                self.migrator_args.database,
                "createSQL",
            ),
        ]
