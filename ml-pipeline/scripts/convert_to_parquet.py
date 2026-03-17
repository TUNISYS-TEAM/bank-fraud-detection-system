#!/usr/bin/env python3
"""
Convert CSV file to Parquet format
Usage: python convert_to_parquet.py --input input.csv --output output.parquet
"""

import pandas as pd
import argparse
import sys
import logging
from pathlib import Path

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

def convert_csv_to_parquet(input_file, output_file):
    """Convert CSV to Parquet with proper data type inference"""
    try:
        logger.info(f"Reading CSV file: {input_file}")
        
        # Read CSV with automatic type detection
        df = pd.read_csv(input_file)
        
        logger.info(f"CSV loaded: {len(df)} rows, {len(df.columns)} columns")
        logger.info(f"Columns: {list(df.columns)}")
        
        # Convert to Parquet
        logger.info(f"Writing Parquet file: {output_file}")
        df.to_parquet(output_file, index=False, compression='snappy')
        
        logger.info("Conversion completed successfully")
        return True
        
    except Exception as e:
        logger.error(f"Conversion failed: {str(e)}")
        return False

def main():
    parser = argparse.ArgumentParser(description='Convert CSV to Parquet')
    parser.add_argument('--input', required=True, help='Input CSV file path')
    parser.add_argument('--output', required=True, help='Output Parquet file path')
    
    args = parser.parse_args()
    
    # Validate input file exists
    if not Path(args.input).exists():
        logger.error(f"Input file not found: {args.input}")
        sys.exit(1)
    
    # Create output directory if it doesn't exist
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    # Perform conversion
    success = convert_csv_to_parquet(args.input, args.output)
    
    if success:
        logger.info(f"Successfully converted {args.input} to {args.output}")
        sys.exit(0)
    else:
        logger.error("Conversion failed")
        sys.exit(1)

if __name__ == "__main__":
    main()