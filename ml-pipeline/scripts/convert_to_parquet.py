#!/usr/bin/env python3
"""
Convert CSV file to Parquet format using DuckDB.
Usage: python convert_to_parquet.py --input input.csv --output output.parquet
"""

import duckdb
import argparse
import sys
import logging
from pathlib import Path

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


def convert_csv_to_parquet(input_file: str, output_file: str) -> bool:
    try:
        logger.info(f"Converting {input_file} → {output_file}")

        duckdb.execute(f"""
            COPY (
                SELECT * FROM read_csv('{input_file}', auto_detect=true)
            ) TO '{output_file}' (
                FORMAT PARQUET,
                COMPRESSION SNAPPY
            )
        """)

        logger.info("Done")
        return True

    except Exception as e:
        logger.error(f"Conversion failed: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description='Convert CSV to Parquet (DuckDB)')
    parser.add_argument('--input',  required=True, help='Input CSV file path')
    parser.add_argument('--output', required=True, help='Output Parquet file path')

    args = parser.parse_args()

    if not Path(args.input).exists():
        logger.error(f"Input file not found: {args.input}")
        sys.exit(1)

    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    sys.exit(0 if convert_csv_to_parquet(args.input, args.output) else 1)


if __name__ == "__main__":
    main()