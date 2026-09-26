#!/usr/bin/env python3
"""
fetch_telegram_release.py - Detects and fetches new Swift Backup releases from
https://t.me/swiftbackupupdates, runs the DEX invariant scanner, and updates
class maps in SwiftBackupPrem.
"""

import argparse
import os
import re
import sys
import urllib.request

# Add current tools directory to sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scan_swiftbackup import (
    parse_manifest,
    scan_apk,
    format_kotlin_entry,
    update_dexkit_kt,
    update_tests,
    update_reverse_engineering_doc,
)

TELEGRAM_CHANNEL = "swiftbackupupdates"
TELEGRAM_WEB_URL = f"https://t.me/s/{TELEGRAM_CHANNEL}"

def get_latest_channel_releases():
    """Scrapes the public Telegram web preview for published Swift Backup APK releases."""
    req = urllib.request.Request(
        TELEGRAM_WEB_URL,
        headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"}
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            html = resp.read().decode("utf-8", "ignore")
    except Exception as e:
        print(f"[!] Error fetching {TELEGRAM_WEB_URL}: {e}")
        return []

    # Pattern for document titles in message widgets
    # e.g.: tgme_widget_message_document_title accent_color" dir="auto">v5.1.1_Beta2 (626).apk</div>
    pattern = r'<a class="tgme_widget_message_document_wrap" href="https://t\.me/[^/]+/(\d+)">[\s\S]*?<div class="tgme_widget_message_document_title[^>]*>([^<]+)</div>'
    matches = re.findall(pattern, html)

    releases = []
    for msg_id, doc_title in matches:
        if not doc_title.lower().endswith(".apk"):
            continue
        # Extract version code: (626)
        m = re.search(r"\((\d+)\)\.apk", doc_title)
        vcode = int(m.group(1)) if m else None
        
        # Extract version name: v5.1.1_Beta2
        m_vname = re.search(r"v?([0-9]+\.[0-9]+[a-zA-Z0-9_\.]*)", doc_title)
        vname = m_vname.group(1) if m_vname else doc_title

        releases.append({
            "msg_id": int(msg_id),
            "doc_title": doc_title.strip(),
            "version_code": vcode,
            "version_name": vname,
            "url": f"https://t.me/{TELEGRAM_CHANNEL}/{msg_id}",
        })

    return releases

def get_mapped_versions(dexkit_file: str) -> set:
    """Reads mapped version codes from DexKit.kt."""
    if not os.path.exists(dexkit_file):
        return set()
    with open(dexkit_file, "r", encoding="utf-8") as f:
        content = f.read()
    codes = re.findall(r"(\d+)\s+to\s+VersionClasses\(", content)
    return set(int(c) for c in codes)

def find_local_apk(version_code: int):
    """Searches standard local download paths for an APK matching the version code."""
    search_dirs = [
        os.path.expanduser("~/Downloads/AyuGram Desktop"),
        os.path.expanduser("~/Downloads/Telegram Desktop"),
        os.path.expanduser("~/Downloads"),
        "/tmp",
        os.getcwd()
    ]
    for d in search_dirs:
        if not os.path.exists(d):
            continue
        for f in os.listdir(d):
            if f.endswith(".apk") and f"({version_code})" in f:
                return os.path.join(d, f)
    return None

def download_via_telethon(msg_id: int, output_path: str) -> bool:
    """Downloads an APK document from Telegram channel using Telethon MTProto."""
    api_id = os.environ.get("TG_API_ID")
    api_hash = os.environ.get("TG_API_HASH")
    session = os.environ.get("TG_SESSION", "sbp_tg_session")
    bot_token = os.environ.get("TG_BOT_TOKEN")

    if not api_id or not api_hash:
        return False

    try:
        from telethon.sync import TelegramClient
        from telethon.sessions import StringSession
        
        session_obj = StringSession(session) if session and len(session) > 50 else session
        client = TelegramClient(session_obj, int(api_id), api_hash)
        if bot_token:
            client.start(bot_token=bot_token)
        else:
            client.connect()
            if not client.is_user_authorized():
                print("[!] Telethon session is not authorized.")
                return False
        with client:
            message = client.get_messages(TELEGRAM_CHANNEL, ids=msg_id)
            if message and message.media:
                file_name = message.file.name if message.file else f"msg_{msg_id}.apk"
                print(f"[*] Downloading message {msg_id} media via Telethon ({file_name})...")
                client.download_media(message, file=output_path)
                return os.path.exists(output_path)
    except Exception as e:
        print(f"[!] Telethon download failed: {e}")
    return False

def main():
    parser = argparse.ArgumentParser(description="Fetch and scan new releases from Telegram.")
    parser.add_argument("--apk", help="Explicit path to APK file (bypasses Telegram download)")
    parser.add_argument("--check-only", action="store_true", help="Only check for new releases without updating code")
    parser.add_argument("--update-code", action="store_true", default=True, help="Automatically update DexKit.kt, tests, and docs")
    parser.add_argument("--all-unmapped", action="store_true", help="Include historical unmapped releases below current max version")
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dexkit_kt = os.path.join(repo_root, "app/src/main/java/io/github/s1ddhants1/swiftbackupprem/DexKit.kt")
    test_kt = os.path.join(repo_root, "app/src/test/java/io/github/s1ddhants1/swiftbackupprem/DexKitVersionMapTest.kt")
    doc_file = os.path.join(repo_root, "docs/REVERSE_ENGINEERING.md")

    mapped_codes = get_mapped_versions(dexkit_kt)
    max_mapped = max(mapped_codes) if mapped_codes else 0
    print(f"[*] Currently mapped versionCodes in DexKit.kt: {sorted(list(mapped_codes))} (latest: {max_mapped})")

    print(f"[*] Querying Telegram channel @{TELEGRAM_CHANNEL}...")
    releases = get_latest_channel_releases()
    if not releases:
        print("[!] No releases found or failed to scrape Telegram channel.")
        sys.exit(0)

    # Sort by version code descending
    releases = sorted([r for r in releases if r["version_code"]], key=lambda x: x["version_code"], reverse=True)
    latest = releases[0]
    print(f"[*] Latest channel release: {latest['doc_title']} (versionCode: {latest['version_code']}, URL: {latest['url']})")

    if args.all_unmapped:
        new_releases = [r for r in releases if r["version_code"] not in mapped_codes]
    else:
        new_releases = [r for r in releases if r["version_code"] and r["version_code"] > max_mapped]

    if not new_releases:
        print("[+] All relevant Telegram channel releases are already mapped in DexKit.kt! Codebase is up-to-date.")
        if "GITHUB_OUTPUT" in os.environ:
            with open(os.environ["GITHUB_OUTPUT"], "a") as f:
                f.write("new_version_found=false\n")
        sys.exit(0)

    print(f"[!] Found {len(new_releases)} unmapped release(s): {[r['doc_title'] for r in new_releases]}")

    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write("new_version_found=true\n")
            f.write(f"version_code={new_releases[0]['version_code']}\n")
            f.write(f"version_name={new_releases[0]['version_name']}\n")
            f.write(f"doc_title={new_releases[0]['doc_title']}\n")
            f.write(f"url={new_releases[0]['url']}\n")

    if args.check_only:
        sys.exit(0)

    updated_count = 0
    # Process each unmapped release (starting from lowest unmapped to latest)
    for rel in reversed(new_releases):
        vcode = rel["version_code"]
        vname = rel["version_name"]
        print(f"\n=======================================================")
        print(f"[*] Processing release: {rel['doc_title']} (code {vcode})")
        print(f"=======================================================")

        apk_path = args.apk
        if not apk_path or not os.path.exists(apk_path):
            apk_path = find_local_apk(vcode)

        if not apk_path:
            # Try telethon download if credentials configured
            candidate_download = os.path.join(repo_root, f"SwiftBackup_{vcode}.apk")
            if download_via_telethon(rel["msg_id"], candidate_download):
                apk_path = candidate_download

        if not apk_path or not os.path.exists(apk_path):
            print(f"[!] APK for versionCode {vcode} could not be downloaded automatically.")
            if not os.environ.get("TG_API_ID") or not os.environ.get("TG_API_HASH"):
                print("[!] Reason: TG_API_ID and TG_API_HASH secrets are not configured.")
            elif not os.environ.get("TG_SESSION") and not os.environ.get("TG_BOT_TOKEN"):
                print("[!] Reason: TG_SESSION secret is not configured.")
            print(f"[!] Telegram release URL: {rel['url']}")
            print(f"[!] To resolve: add TG_API_ID, TG_API_HASH, and TG_SESSION to repository secrets,")
            print(f"    or trigger workflow_dispatch with 'apk_url', or scan locally with:")
            print(f"    python3 tools/scan_swiftbackup.py --apk <path_to_apk> --update-code")
            continue

        print(f"[*] Scanning APK: {apk_path}...")
        classes = scan_apk(apk_path)
        print(f"[+] Successfully extracted {len(classes)} target classes.")

        entry_str = format_kotlin_entry(vcode, classes)
        print(f"\nGenerated Kotlin Entry:\n{entry_str}\n")

        if args.update_code:
            update_dexkit_kt(dexkit_kt, vcode, entry_str)
            update_tests(test_kt, vcode, classes)
            update_reverse_engineering_doc(doc_file, vcode, classes)
            print(f"[+] Successfully updated codebase for Swift Backup {vname} ({vcode})!")
            updated_count += 1

    if updated_count == 0 and new_releases:
        print(f"\n[!] Failed to download and update any of the {len(new_releases)} unmapped release(s).")
        sys.exit(2)

if __name__ == "__main__":
    main()
