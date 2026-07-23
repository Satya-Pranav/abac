from onetrust_synth.validate import validate_row_counts, validate_referential_integrity
from onetrust_synth.generate_main_tables import build_all_main_tables
from onetrust_synth.generate_abac_tables import build_all_abac_tables
from onetrust_synth.registries import build_entity_registry, build_subject_registry
from onetrust_synth import config


def test_validate_row_counts_passes_on_exact_match():
    built = {"a": 100, "b": 200}
    targets = {"a": 100, "b": 200}
    assert validate_row_counts(built, targets) == []


def test_validate_row_counts_flags_mismatch_beyond_tolerance():
    built = {"a": 50}
    targets = {"a": 100}
    failures = validate_row_counts(built, targets, tolerance=0.05)
    assert len(failures) == 1
    assert "a" in failures[0]


def test_validate_referential_integrity_reports_full_match(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    subj_reg = build_subject_registry(spark)
    abac_tables = build_all_abac_tables(spark, main_tables)

    report = validate_referential_integrity(
        abac_tables["ABAC_EntitySubjectAssignment"], entity_reg, subj_reg, abac_tables["ABAC_Assignment"],
    )
    assert report["entity_match_rate"] == 1.0
    assert report["subject_match_rate"] == 1.0
    assert report["assignment_match_rate"] == 1.0
