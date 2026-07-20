from databricks.sdk import WorkspaceClient

from pyspark.sql import DataFrame
from app.common import functions
from app.migrations import migrator


class V20260710S002_create_abac_policies(migrator.SQLOAuthMigration):
    def get_script(self) -> str | list[str] | None:
        db = self.migrator_args.database
        default_policy = functions.getSQL("ABAC", "policy_default", db, "policySQL")
        no_type_policy = functions.getSQL("ABAC", "policy_no_type", db, "policySQL")

        principals = ", ".join(
            [
                "`" + sp.application_id + "`"
                for sp in self.migrator_args.migrator.client.service_principals.list(
                    filter="displayName eq 'databricks-abac-service-principal'"
                )
            ]
        )

        default_policy = default_policy.replace("@SERVICE_PRINCIPAL", principals)
        no_type_policy = no_type_policy.replace("@SERVICE_PRINCIPAL", principals)

        return [
            default_policy,
            *[
                no_type_policy.replace("@TABLE", table).replace("@TYPE", type)
                for table, type in [
                    ("CMB_Assessment", "Assessment"),
                    ("CMB_VendorContract", "Contract"),
                    ("CMB_ControlImplementation", "Control"),
                    ("CMB_Risk", "Risk"),
                ]
            ],
        ]
