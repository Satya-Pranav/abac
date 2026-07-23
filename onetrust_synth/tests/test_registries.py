from onetrust_synth import config
from onetrust_synth.registries import build_org_registry, build_subject_registry


def test_org_registry_reuses_real_orghierarchy(spark):
    reg = build_org_registry(spark)
    assert reg.count() == 183
    assert set(reg.columns) >= {"orgId", "parentOrgId"}


def test_subject_registry_has_users_and_groups(spark):
    reg = build_subject_registry(spark)
    total = config.SUBJECT_REGISTRY_USER_COUNT + config.SUBJECT_REGISTRY_GROUP_COUNT
    assert reg.count() == total
    types = {r["subjectType"] for r in reg.select("subjectType").distinct().collect()}
    assert types == {"USER_ID", "USER_GROUP"}
    user_count = reg.filter(reg.subjectType == "USER_ID").count()
    assert user_count == config.SUBJECT_REGISTRY_USER_COUNT


def test_subject_registry_ids_are_unique(spark):
    reg = build_subject_registry(spark)
    ids = [r["subjectId"] for r in reg.select("subjectId").collect()]
    assert len(ids) == len(set(ids))
