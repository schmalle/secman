import pandas as pd
import openai
from openpyxl import load_workbook
import time
import json
import os
import argparse

class IEC62443Matcher:
    def __init__(self, api_key, excel_file_path):
        self.client = openai.OpenAI(api_key=api_key)
        self.excel_file = excel_file_path
        self.iec_standards = self.load_iec_standards()
    
    def load_iec_standards(self):
        """Load IEC 62443 standards mapping"""
        return {
            "IEC 62443-1-1": "Security for industrial automation and control systems - Models and concepts",
            "IEC 62443-2-1": "Establishing an industrial automation and control systems security program",
            "IEC 62443-2-4": "Security program requirements for IACS service providers",
            "IEC 62443-3-1": "Security technologies for industrial automation and control systems",
            "IEC 62443-3-2": "Security risk assessment for system integrators",
            "IEC 62443-3-3": "System security requirements and security levels",
            "IEC 62443-4-1": "Secure product development lifecycle requirements",
            "IEC 62443-4-2": "Technical security requirements for IACS components"
        }
    
    def create_matching_prompt(self, requirement):
        """Create a prompt for AI matching"""
        standards_text = "\n".join([f"{code}: {desc}" for code, desc in self.iec_standards.items()])
        
        prompt = f"""
        Given this industrial security requirement:
        "{requirement}"
        
        Match it to the most appropriate IEC 62443 standard from this list:
        {standards_text}
        
        Respond with ONLY the standard code (e.g., "IEC 62443-3-3") that best matches the requirement.
        If no good match exists, respond with "No clear match".
        """
        return prompt
    
    def match_requirement_to_standard(self, requirement):
        """Use AI to match requirement to IEC standard"""
        try:
            prompt = self.create_matching_prompt(requirement)
            
            response = self.client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=[{"role": "user", "content": prompt}],
                max_tokens=50,
                temperature=0.1
            )
            
            result = response.choices[0].message.content.strip()
            
            # Validate the response
            if result in self.iec_standards or result == "No clear match":
                return result
            else:
                return "Invalid AI response"
                
        except Exception as e:
            print(f"Error matching requirement '{requirement[:50]}...': {str(e)}")
            return "Error in matching"
    
    def process_excel_file(self):
        """Process the Excel file and add IEC matches"""
        try:
            # Load the Excel file
            df = pd.read_excel(self.excel_file)
            
            # Ensure we have the right columns
            if 'B' not in df.columns:
                print("Column B not found. Available columns:", df.columns.tolist())
                return
            
            # Process each requirement
            matches = []
            for idx, requirement in enumerate(df['B']):
                if pd.isna(requirement):
                    matches.append("Empty requirement")
                    continue
                    
                print(f"Processing row {idx + 1}: {str(requirement)[:50]}...")
                match = self.match_requirement_to_standard(str(requirement))
                matches.append(match)
                
                # Add delay to respect API rate limits
                time.sleep(0.5)
            
            # Add matches to column H
            df['H'] = matches
            
            # Save back to Excel
            with pd.ExcelWriter(self.excel_file, engine='openpyxl', mode='a', if_sheet_exists='overlay') as writer:
                df.to_excel(writer, index=False)
            
            print(f"Successfully processed {len(matches)} requirements!")
            return df
            
        except Exception as e:
            print(f"Error processing Excel file: {str(e)}")
            return None
    
    def create_summary_report(self, df):
        """Create a summary of the matching results"""
        if df is None:
            return
            
        summary = df['H'].value_counts()
        print("\n=== MATCHING SUMMARY ===")
        for standard, count in summary.items():
            print(f"{standard}: {count} requirements")

# Usage example
def main():
    # Configuration
    OPENAI_API_KEY = os.environ.get("OPENAI_KEY")
    if not OPENAI_API_KEY:
        print("Error: OPENAI_API_KEY environment variable not set.")
        return
    
    parser = argparse.ArgumentParser(description="Match requirements in an Excel file to IEC 62443 standards.")
    parser.add_argument("excel_file", help="Path to the Excel file to process.")
    args = parser.parse_args()

    EXCEL_FILE_PATH = args.excel_file
    
    # Initialize matcher
    matcher = IEC62443Matcher(OPENAI_API_KEY, EXCEL_FILE_PATH)
    
    # Process the file
    result_df = matcher.process_excel_file()
    
    # Generate summary
    matcher.create_summary_report(result_df)

if __name__ == "__main__":
    main()