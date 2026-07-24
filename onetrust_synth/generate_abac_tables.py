# onetrust_synth/generate_abac_tables.py
from pyspark.sql import SparkSession

from onetrust_synth import config
from onetrust_synth.registries import build_org_registry, build_subject_registry, build_entity_registry
from onetrust_synth.abac_tables import (
    build_abac_assignment, build_abac_assignment_permission,
    build_abac_entity_subject_assignment, build_user_group_members, build_org_hierarchy_base,
    build_org_hierarchy_view_sql,
)
from onetrust_synth.write import write_delta_table


def build_all_abac_tables(spark: SparkSession, main_tables: dict) -> dict:
    entity_registry = build_entity_registry(spark, main_tables)
    org_registry = build_org_registry(spark)
    subject_registry = build_subject_registry(spark)

    assignment = build_abac_assignment(spark, config.ABAC_TABLE_ROW_TARGETS["ABAC_Assignment"])
    assignment_permission = build_abac_assignment_permission(
        spark, assignment, config.ABAC_TABLE_ROW_TARGETS["ABAC_AssignmentPermission"]
    )
    esa = build_abac_entity_subject_assignment(
        spark, assignment, entity_registry, org_registry, subject_registry,
        config.ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"],
    )
    user_group_members = build_user_group_members(spark, subject_registry, config.ABAC_TABLE_ROW_TARGETS["UserGroupMembers"])
    org_hierarchy_base = build_org_hierarchy_base(spark)

    return {
        "ABAC_Assignment": assignment,
        "ABAC_AssignmentPermission": assignment_permission,
        "ABAC_EntitySubjectAssignment": esa,
        "UserGroupMembers": user_group_members,
        "ABAC_OrgHierarchy": org_hierarchy_base,  # written as OrgHierarchyBase; view created separately, see Task 17
    }


def main():
    from onetrust_synth.generate_main_tables import build_all_main_tables

    spark = SparkSession.builder.appName("onetrust_synth-abac-tables").getOrCreate()
    main_tables = build_all_main_tables(spark, scale_factor=config.SCALE_FACTOR_DEFAULT)
    abac_tables = build_all_abac_tables(spark, main_tables)

    for table_name, df in abac_tables.items():
        write_table_name = "OrgHierarchyBase" if table_name == "ABAC_OrgHierarchy" else table_name
        partition_by = ["objectType"] if table_name in config.ABAC_PARTITIONED_TABLES else None
        write_delta_table(df, config.CATALOG, config.MAIN_SCHEMA, write_table_name, partition_by=partition_by)
        print(f"Wrote {config.CATALOG}.{config.MAIN_SCHEMA}.{write_table_name}: {df.count()} rows")

    # OrgHierarchyBase is the physical table written above; ABAC_OrgHierarchy is a view
    # over it (named to avoid a case-insensitive collision with the real, separately-generated
    # lowercase "orghierarchy" main table in the same schema — Unity Catalog identifiers are
    # case-insensitive, so "OrgHierarchy" and "orghierarchy" are the same name to it).
    # Task 17's row-filter UDF reads ABAC_OrgHierarchy (the view), so this must run before
    # that UDF is ever invoked.
    spark.sql(build_org_hierarchy_view_sql())
    print(f"Created view {config.CATALOG}.{config.MAIN_SCHEMA}.ABAC_OrgHierarchy over OrgHierarchyBase")


if __name__ == "__main__":
    main()
