#!/usr/bin/env python3
"""
generate_telegram_session.py - Helper script to generate a Telethon StringSession.
Run this locally once to authenticate your Telegram account and obtain a session string
for use in GitHub Actions secrets (TG_SESSION).
"""
import os
import subprocess
import sys

# Auto re-exec into virtualenv if telethon is not in current interpreter
try:
    from telethon.sync import TelegramClient
    from telethon.sessions import StringSession
except ImportError:
    venv_py = "/tmp/sbp_tg_venv/bin/python3"
    if os.path.exists(venv_py) and sys.executable != venv_py:
        os.execv(venv_py, [venv_py] + sys.argv)
    else:
        print("[!] Telethon is not installed. Please install it with:")
        print("    pip install telethon")
        sys.exit(1)

DEFAULT_API_ID = 2040
DEFAULT_API_HASH = "b18441a1ff607e10a989891a5462e627"

def main():
    print("=" * 65)
    print(" Telegram Session String Generator for SwiftBackupPrem CI")
    print("=" * 65)
    print(f"Using Telegram Desktop official API credentials:\n  API_ID:   {DEFAULT_API_ID}\n  API_HASH: {DEFAULT_API_HASH}\n")
    
    choice = input("Press [Enter] to use these default credentials (or type 'custom' to enter your own): ").strip().lower()
    if choice == "custom":
        api_id_input = input("Enter TG_API_ID: ").strip()
        api_hash = input("Enter TG_API_HASH: ").strip()
        try:
            api_id = int(api_id_input)
        except ValueError:
            print("[!] API ID must be an integer.")
            sys.exit(1)
    else:
        api_id = DEFAULT_API_ID
        api_hash = DEFAULT_API_HASH

    print("\n[*] Initializing Telegram client...")
    print("[*] You will be asked for your phone number (+countrycode...) and login code (sent to Telegram app).\n")
    with TelegramClient(StringSession(), api_id, api_hash) as client:
        session_str = client.session.save()
        print("\n" + "=" * 65)
        print("[+] SUCCESS! Here is your TG_SESSION string:\n")
        print(session_str)
        print("=" * 65)

        # Offer to set GitHub Secrets automatically via gh CLI
        auto_set = input("\nWould you like to automatically upload these secrets to GitHub via 'gh'? (Y/n): ").strip().lower()
        if auto_set != "n":
            try:
                repo = "s1ddhants1/SwiftBackupPrem"
                print(f"[*] Setting TG_API_ID in GitHub secrets ({repo})...")
                subprocess.run(["gh", "secret", "set", "TG_API_ID", "-R", repo, "--body", str(api_id)], check=True)
                print(f"[*] Setting TG_API_HASH in GitHub secrets ({repo})...")
                subprocess.run(["gh", "secret", "set", "TG_API_HASH", "-R", repo, "--body", api_hash], check=True)
                print(f"[*] Setting TG_SESSION in GitHub secrets ({repo})...")
                subprocess.run(["gh", "secret", "set", "TG_SESSION", "-R", repo, "--body", session_str], check=True)
                print("\n[+] All Telegram secrets successfully configured in GitHub repository!")
            except Exception as e:
                print(f"[!] Could not set GitHub secrets automatically: {e}")
                print("You can add them manually in GitHub repository settings -> Secrets.")

if __name__ == "__main__":
    main()
