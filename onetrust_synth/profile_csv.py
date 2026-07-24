import csv
from dataclasses import dataclass


@dataclass
class ColumnProfile:
    name: str
    data_type: str
    ndv: int
    null_rate: float
    min_val: str | None
    max_val: str | None


def _clean_lines(f):
    for line in f:
        yield line.replace("\0", "")


def load_table_profile(csv_path: str) -> dict[tuple[str, str], list[ColumnProfile]]:
    result: dict[tuple[str, str], list[ColumnProfile]] = {}
    with open(csv_path, newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(_clean_lines(f))
        for row in reader:
            key = (row["schema"], row["table"])
            row_count = float(row["row_count"] or 0)
            null_count_raw = row.get("null_count") or ""
            ndv_raw = row.get("ndv") or ""
            null_rate = 0.0
            if null_count_raw.strip() and row_count > 0:
                null_rate = float(null_count_raw) / row_count
            ndv = int(float(ndv_raw)) if ndv_raw.strip() else 0
            col = ColumnProfile(
                name=row["column_name"],
                data_type=row["data_type"],
                ndv=ndv,
                null_rate=null_rate,
                min_val=row.get("min_val") or None,
                max_val=row.get("max_val") or None,
            )
            result.setdefault(key, []).append(col)
    return result


def get_columns(profile: dict[tuple[str, str], list[ColumnProfile]], schema: str, table: str) -> list[ColumnProfile]:
    return profile[(schema, table)]
