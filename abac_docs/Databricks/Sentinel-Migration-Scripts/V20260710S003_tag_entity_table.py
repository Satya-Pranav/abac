from app.migrations.migrator import ABACTagMigration


class V20260710S003_tag_entity_table(ABACTagMigration):
    def get_table(self) -> str:
        return "Entity"

    def get_id_column(self) -> str:
        return "entityID"

    def get_type_column(self) -> str | None:
        return "entityTypeReference"

    def get_org_column(self) -> str | None:
        return "orgID"
