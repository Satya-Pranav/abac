from app.migrations import migrator


class V20260710S001_create_ABAC_tags(migrator.GovernedTagMigration):
    def get_tags(self) -> list[tuple[str, str] | tuple[str, str, list[str]]]:
        return [
            ("abac_column_id", "Marks the ID column"),
            ("abac_column_type", "Marks the ABAC type column"),
            ("abac_column_org", "Marks the organization ID column"),
            ("abac_column_tenant", "Marks the tenant ID column, for use with Silverin"),
        ]
