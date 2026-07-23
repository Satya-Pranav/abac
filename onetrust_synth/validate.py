# onetrust_synth/validate.py
from pyspark.sql import DataFrame


def validate_row_counts(built: dict, targets: dict, tolerance: float = 0.05) -> list:
    failures = []
    for table, target in targets.items():
        actual = built.get(table)
        if actual is None:
            failures.append(f"{table}: missing from built tables")
            continue
        if target == 0:
            if actual != 0:
                failures.append(f"{table}: expected 0 rows, got {actual}")
            continue
        deviation = abs(actual - target) / target
        if deviation > tolerance:
            failures.append(f"{table}: expected ~{target}, got {actual} ({deviation:.1%} off)")
    return failures


def validate_referential_integrity(esa_df: DataFrame, entity_registry: DataFrame, subject_registry: DataFrame, assignment_df: DataFrame) -> dict:
    total = esa_df.count()
    if total == 0:
        return {"entity_match_rate": 1.0, "subject_match_rate": 1.0, "assignment_match_rate": 1.0}

    entity_matches = esa_df.join(
        entity_registry.select("entityId", "objectType"), on=["entityId", "objectType"], how="inner"
    ).count()
    subject_matches = esa_df.join(
        subject_registry.select("subjectId", "subjectType"), on=["subjectId", "subjectType"], how="inner"
    ).count()
    assignment_matches = esa_df.join(
        assignment_df.select(assignment_df.id.alias("assignmentId")), on="assignmentId", how="inner"
    ).count()

    return {
        "entity_match_rate": entity_matches / total,
        "subject_match_rate": subject_matches / total,
        "assignment_match_rate": assignment_matches / total,
    }
