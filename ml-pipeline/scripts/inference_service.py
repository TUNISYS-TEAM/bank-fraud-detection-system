#!/usr/bin/env python3
"""
Inference service - with currency conversion
"""

import argparse
import json
import logging
import sys
import pandas as pd
import numpy as np
import joblib
import re
from datetime import datetime

sys.path.insert(0, '/app/src')
from ml_pipeline.utils.postal_codes import get_resolver

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


def ip_to_numeric(ip):
    """Convert IP address string to numeric value"""
    if ip is None or ip == 'Unknown' or pd.isna(ip):
        return 0
    try:
        parts = str(ip).split('.')
        if len(parts) == 4:
            return int(parts[0]) * 256**3 + int(parts[1]) * 256**2 + int(parts[2]) * 256 + int(parts[3])
    except Exception:
        pass
    return 0


def currency_to_numeric(currency):
    """Convert currency to numeric code"""
    currency_map = {
        'TND': 1,
        'USD': 2,
        'EUR': 3,
        'GBP': 4,
        'JPY': 5,
        'CAD': 6,
        'AUD': 7,
        'CHF': 8,
        'CNY': 9,
        'Unknown': 0
    }
    return currency_map.get(str(currency), 0)


def extract_numeric_from_id(value):
    """Extract numeric part from ID strings"""
    if pd.isna(value):
        return 0
    try:
        if isinstance(value, (int, float)):
            return float(value)
        s = str(value)
        if '-' in s:
            parts = s.split('-')
            if len(parts) > 1 and parts[1].replace('.', '', 1).isdigit():
                return float(parts[1])
        import re
        numbers = re.findall(r'\d+', s)
        if numbers:
            return float(numbers[0])
        return 0
    except:
        return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-path', required=True)
    parser.add_argument('--input', required=True)
    args = parser.parse_args()
    
    try:
        # Load input data
        with open(args.input, 'r') as f:
            input_data = json.load(f)
        
        logger.info(f"Input data loaded. Keys: {input_data.keys()}")
        
        # Load the full pipeline
        logger.info(f"Loading model from: {args.model_path}")
        pipeline = joblib.load(args.model_path)
        
        # Get transaction and behavior data
        transaction = input_data['transaction']
        existing_behavior = input_data.get('existing_behavior')
        has_existing = input_data.get('has_existing_behavior', False)
        
        # Create DataFrame with raw transaction data
        df = pd.DataFrame([transaction])
        
        # Map database column names to training column names
        column_mapping = {
            'created_at': 'TX_Time',
            'amount': 'TX_Amount',
            'channel': 'Channel',
            'merchant_category': 'category',
            'postal_code_client': 'Customer_Postal_Code',
            'postal_code_merchant': 'Merchant_Postal_Code',
            'device_id': 'Device_Id',
            'ip_address': 'ip_adress',
            'currency': 'currency',
            'account_id': 'Account_Id'
        }
        
        for old_name, new_name in column_mapping.items():
            if old_name in df.columns and new_name not in df.columns:
                df[new_name] = df[old_name]
        
        # Convert currency to numeric
        if 'currency' in df.columns:
            df['currency'] = df['currency'].apply(currency_to_numeric).astype(np.float32)
        else:
            df['currency'] = 1  # Default to TND
        
        # Convert IP address to numeric
        if 'ip_adress' in df.columns:
            df['ip_adress'] = df['ip_adress'].apply(ip_to_numeric).astype(np.float32)
        else:
            df['ip_adress'] = 0
        
        # Process Account_Id and Device_Id to extract numeric parts
        if 'Account_Id' in df.columns:
            df['Account_Id'] = df['Account_Id'].apply(extract_numeric_from_id).astype(np.float32)
        else:
            df['Account_Id'] = 0
        
        if 'Device_Id' in df.columns:
            df['Device_Id'] = df['Device_Id'].apply(extract_numeric_from_id).astype(np.float32)
        else:
            df['Device_Id'] = 0
        
        # Process postal codes to generate Customer_Region and Merchant_Region
        resolver = get_resolver()
        
        # Process Customer_Postal_Code
        if 'Customer_Postal_Code' in df.columns:
            df_customer = resolver.enrich_dataframe(
                df.copy(), postal_col='Customer_Postal_Code', add_gov=False, add_deleg=False, add_region=True
            )
            if 'region' in df_customer.columns:
                # Convert region to numeric code
                region_codes = {'Unknown': 0, 'Grand_Tunis': 1, 'Nord_Est': 2, 'Nord_Ouest': 3, 
                                'Centre_Est': 4, 'Centre_Ouest': 5, 'Sud_Est': 6, 'Sud_Ouest': 7}
                df['Customer_Region'] = df_customer['region'].map(region_codes).fillna(0).astype(np.float32)
                logger.info(f"Customer_Region: {df['Customer_Region'].iloc[0]}")
            else:
                df['Customer_Region'] = 0
        else:
            df['Customer_Region'] = 0
        
        # Process Merchant_Postal_Code
        if 'Merchant_Postal_Code' in df.columns:
            df_merchant = resolver.enrich_dataframe(
                df.copy(), postal_col='Merchant_Postal_Code', add_gov=False, add_deleg=False, add_region=True
            )
            if 'region' in df_merchant.columns:
                # Convert region to numeric code
                region_codes = {'Unknown': 0, 'Grand_Tunis': 1, 'Nord_Est': 2, 'Nord_Ouest': 3, 
                                'Centre_Est': 4, 'Centre_Ouest': 5, 'Sud_Est': 6, 'Sud_Ouest': 7}
                df['Merchant_Region'] = df_merchant['region'].map(region_codes).fillna(0).astype(np.float32)
                logger.info(f"Merchant_Region: {df['Merchant_Region'].iloc[0]}")
            else:
                df['Merchant_Region'] = 0
        else:
            df['Merchant_Region'] = 0
        
        # Ensure TX_Time is datetime
        if 'TX_Time' in df.columns:
            df['TX_Time'] = pd.to_datetime(df['TX_Time'])
        else:
            df['TX_Time'] = datetime.now()
        
        # Add client behavior features
        if existing_behavior:
            for key, value in existing_behavior.items():
                df[key] = value
        else:
            # Default values for first transaction
            df['client_AVG_TX_Amount'] = 0.0
            df['client_AVG_TX_PerDay'] = 0.0
            df['client_amount_deviation'] = 0.0
            df['client_abs_amount_deviation'] = 0.0
            df['client_amount_max_ratio'] = 1.0
            df['client_amount_z_score'] = 0.0
            df['client_is_new_max'] = 0
            df['client_is_new_min'] = 0
            df['client_total_transactions'] = 0
            df['client_time_since_last_tx'] = 0
            df['client_hours_since_last_tx'] = 0.0
            df['client_total_days_since_first'] = 0.0
            df['client_usual_region'] = 'Unknown'
            df['client_is_unusual_region'] = 0
            df['client_region_freq'] = 0.0
            df['client_usual_channel'] = 'Unknown'
            df['client_is_unusual_channel'] = 0
            df['client_channel_freq'] = 0.0
            df['client_usual_category'] = 'Unknown'
            df['client_is_unusual_category'] = 0
            df['client_category_freq'] = 0.0
            df['client_risk_score'] = 0.0
        
        # Fill missing values
        df['Channel'] = df['Channel'].fillna('Unknown')
        df['category'] = df['category'].fillna('Unknown')
        
        # Ensure all required columns are present
        required_columns = [
            'TX_Time', 'TX_Amount', 'Channel', 'category', 'Account_Id', 'Device_Id',
            'Customer_Region', 'Merchant_Region', 'ip_adress', 'currency',
            'client_AVG_TX_Amount', 'client_AVG_TX_PerDay', 'client_amount_deviation',
            'client_abs_amount_deviation', 'client_amount_max_ratio', 'client_amount_z_score',
            'client_is_new_max', 'client_is_new_min', 'client_total_transactions',
            'client_time_since_last_tx', 'client_hours_since_last_tx', 'client_total_days_since_first',
            'client_usual_region', 'client_is_unusual_region', 'client_region_freq',
            'client_usual_channel', 'client_is_unusual_channel', 'client_channel_freq',
            'client_usual_category', 'client_is_unusual_category', 'client_category_freq',
            'client_risk_score'
        ]
        
        for col in required_columns:
            if col not in df.columns:
                if col in ['Customer_Region', 'Merchant_Region']:
                    df[col] = 0
                elif col in ['Channel', 'category', 'client_usual_region', 'client_usual_channel', 'client_usual_category']:
                    df[col] = 'Unknown'
                else:
                    df[col] = 0.0
        
        logger.info(f"Input DataFrame columns: {list(df.columns)}")
        logger.info(f"TX_Time value: {df['TX_Time'].iloc[0]}")
        logger.info(f"Currency: {df['currency'].iloc[0]}")
        logger.info(f"Customer_Region: {df['Customer_Region'].iloc[0]}, Merchant_Region: {df['Merchant_Region'].iloc[0]}")
        
        # Let the pipeline do all the transformations
        proba = pipeline.predict_proba(df)
        fraud_score = float(proba[0][1])
        logger.info(f"Model prediction successful: fraud_score={fraud_score}")
        
        # Determine risk level
        if fraud_score >= 0.8:
            risk_level = "CRITICAL"
        elif fraud_score >= 0.6:
            risk_level = "HIGH"
        elif fraud_score >= 0.4:
            risk_level = "MEDIUM"
        elif fraud_score >= 0.2:
            risk_level = "LOW"
        else:
            risk_level = "VERY_LOW"
        
        # Update behavior for next transaction
        total_tx = (existing_behavior.get('client_total_transactions', 0) if existing_behavior else 0) + 1
        current_avg = existing_behavior.get('client_AVG_TX_Amount', 0) if existing_behavior else 0
        amount = float(transaction.get('TX_Amount', transaction.get('amount', 0)))
        
        if total_tx > 1:
            new_avg = (current_avg * (total_tx - 1) + amount) / total_tx
        else:
            new_avg = amount
        
        updated_behavior = {
            'client_AVG_TX_Amount': new_avg,
            'client_AVG_TX_PerDay': new_avg / 30 if new_avg > 0 else 0,
            'client_total_transactions': total_tx,
            'client_time_since_last_tx': 0,
            'client_risk_score': existing_behavior.get('client_risk_score', 0) * 0.7 + fraud_score * 0.3 if existing_behavior else fraud_score
        }
        
        result = {
            'fraud_score': fraud_score,
            'risk_level': risk_level,
            'alert_type': f"{risk_level}_RISK",
            'supervised_score': fraud_score,
            'anomaly_score': fraud_score * 0.8,
            'updated_behavior': updated_behavior,
            'is_first_transaction': not has_existing
        }
        
        logger.info(f"Prediction result: fraud_score={fraud_score}, risk={risk_level}")
        print(json.dumps(result, default=str))
        
    except Exception as e:
        logger.exception("Inference failed")
        error_result = {'error': str(e)}
        print(json.dumps(error_result))
        sys.exit(1)


if __name__ == '__main__':
    main()