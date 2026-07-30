import csv
import os
import tempfile

from onetrust_synth.assemble_final_csv import assemble

_HEADER = "query_id,source,tables_used,claim,query,expected_or_observed,verified_status\n"


def test_assemble_concatenates_both_csvs_with_one_header():
    with tempfile.TemporaryDirectory() as d:
        real_path = os.path.join(d, "real.csv")
        func_path = os.path.join(d, "func.csv")
        out_path = os.path.join(d, "final.csv")

        with open(real_path, "w") as f:
            f.write(_HEADER)
            f.write('q1,real_query,cmb_assessment,"{}",SELECT 1,3,PASS\n')

        with open(func_path, "w") as f:
            f.write(_HEADER)
            f.write('OT-A1,functional_test,,"{}",SELECT 2,ALL rows,\n')

        total = assemble(real_path, func_path, out_path)
        assert total == 2

        with open(out_path, newline="") as f:
            rows = list(csv.DictReader(f))
        assert len(rows) == 2
        assert {r["query_id"] for r in rows} == {"q1", "OT-A1"}
