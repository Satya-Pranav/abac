"""Concatenates the Python-produced real-query shortlist and the Java-produced functional-test
export into the single final deliverable CSV (design doc section 8) -- both already share the
same 7-column schema, so this is pure concatenation, not a merge."""
import csv


def assemble(real_query_csv: str, functional_test_csv: str, out_path: str) -> int:
    total = 0
    with open(out_path, "w", newline="", encoding="utf-8") as out_f:
        writer = None
        for source_path in (real_query_csv, functional_test_csv):
            with open(source_path, newline="", encoding="utf-8") as in_f:
                reader = csv.DictReader(in_f)
                if writer is None:
                    writer = csv.DictWriter(out_f, fieldnames=reader.fieldnames)
                    writer.writeheader()
                for row in reader:
                    writer.writerow(row)
                    total += 1
    return total
