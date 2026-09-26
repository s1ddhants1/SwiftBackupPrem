#!/usr/bin/env python3
"""
generate_telegram_session.py - Helper script to generate a Telethon StringSession.
Run this locally once to authenticate your Telegram account and obtain a session string
for use in GitHub Actions secrets (TG_SESSION).
"""
import sys

try:
    from telethon.sync import TelegramClient
    from telethon.sessions import StringSession
except ImportError:
    print("[!] Telethon is not installed. Please install it with:")
    print("    pip install telethon")
    sys.exit(1)

def main():
    print("=" * 65)
    print(" Telegram Session String Generator for SwiftBackupPrem CI")
    print("=" * 65)
    print("Get your API ID and API Hash from: https://my.telegram.org (under 'API development tools')\n")
    
    api_id_input = input("Enter TG_API_ID: ").strip()
    api_hash = input("Enter TG_API_HASH: ").strip()
    
    if not api_id_input or not api_hash:
        print("[!] Both API ID and API Hash are required.")
        sys.exit(1)
        
    try:
        api_id = int(api_id_input)
    except ValueError:
        print("[!] API ID must be a numeric integer.")
        sys.exit(1)
        
    print("\n[*] Initializing Telegram client...")
    print("[*] Telegram will send a login code via the Telegram app.")
    with TelegramClient(StringSession(), api_id, api_hash) as client:
        session_str = client.session.save()
        print("\n" + "=" * 65)
        print("[+] SUCCESS! Here is your TG_SESSION string:\n")
        print(session_str)
        print("\n" + "=" * 65)
        print("\nNext steps:")
        print("1. Go to your GitHub repository -> Settings -> Secrets and variables -> Actions")
        print(f"2. Add Repository Secret: TG_API_ID = {api_id}")
        print(f"3. Add Repository Secret: TG_API_HASH = {api_hash}")
        print("4. Add Repository Secret: TG_SESSION = <the string printed above>")
        print("\nOnce added, GitHub Actions will automatically download and scan new APKs!")

if __name__ == "__main__":
    main()
