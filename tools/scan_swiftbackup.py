#!/usr/bin/env python3
"""
scan_swiftbackup.py - Offline DEX Invariant Scanner for Swift Backup releases.
Scans multi-DEX Swift Backup APKs in pure Python without external dependencies,
resolving all 16 obfuscated target classes used by SwiftBackupPrem hooks.
"""

import argparse
import os
import re
import struct
import sys
import zipfile

def uleb128(data: bytes, off: int):
    res = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        res |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return res, off

def parse_manifest(apk_path: str):
    """Extract versionCode and versionName from AndroidManifest.xml (AXML)."""
    with zipfile.ZipFile(apk_path, "r") as z:
        if "AndroidManifest.xml" not in z.namelist():
            return None, None
        data = z.read("AndroidManifest.xml")

    # AXML binary parser
    chunk_type, chunk_size = struct.unpack("<II", data[8:16])
    string_count, _, flags, strings_start, _ = struct.unpack("<IIIII", data[16:36])
    offsets = struct.unpack(f"<{string_count}I", data[36 : 36 + string_count * 4])
    pool_data = data[8 + strings_start :]
    
    strings = []
    is_utf8 = (flags & (1 << 8)) != 0
    for off in offsets:
        if is_utf8:
            end = pool_data.find(b"\x00", off)
            strings.append(pool_data[off + 2 : end].decode("utf-8", "ignore"))
        else:
            char_len = struct.unpack("<H", pool_data[off : off + 2])[0]
            s = pool_data[off + 2 : off + 2 + char_len * 2].decode("utf-16le", "ignore")
            strings.append(s)

    version_code = None
    version_name = None
    pos = 8 + chunk_size
    while pos < len(data):
        c_type, c_size = struct.unpack("<II", data[pos : pos + 8])
        if c_type == 0x00100102: # START_TAG
            line, comment, ns, name, attr_start, attr_size, attr_count, id_idx, class_idx, style_idx = struct.unpack("<IIIIHHHHHH", data[pos + 8 : pos + 36])
            tag_name = strings[name] if name < len(strings) else ""
            if tag_name == "manifest":
                attr_pos = pos + 36
                for _ in range(attr_count):
                    _, aname, aval_str, _, adata = struct.unpack("<IIIII", data[attr_pos : attr_pos + 20])
                    attr_name = strings[aname] if aname < len(strings) else ""
                    if attr_name == "versionCode":
                        version_code = adata
                    elif attr_name == "versionName":
                        version_name = strings[aval_str] if aval_str < len(strings) else str(adata)
                    attr_pos += attr_size
        pos += c_size

    # Fallback to filename pattern if manifest attributes were stripped
    if version_code is None:
        m = re.search(r"\((\d+)\)\.apk", os.path.basename(apk_path))
        if m:
            version_code = int(m.group(1))
    if version_name is None:
        m = re.search(r"v?([0-9]+\.[0-9]+[a-zA-Z0-9_\.]*)", os.path.basename(apk_path))
        if m:
            version_name = m.group(1)

    return version_code, version_name

def scan_apk(apk_path: str):
    """Scans all DEX slices in the APK and resolves obfuscated hook targets."""
    target_strings = {
        "org.swiftapps.swiftbackup:/oauth",
        "setup_cloud_first_startup",
        "KEY_SCHEDULE_ENABLED",
        "clearAnonymousSignIn",
        "anonymous@swiftbackup.app",
        "client ID cannot be null or empty",
        "apkBackupDate",
        "dataBackupDate",
        "dateBackupUpdated",
        "minSBVersionCodeRequired",
        "FCW",
        ".info/connected",
        "FireSynchronizer",
        "Maps with non-string keys are not supported",
        "backup_storage_location",
        "app_backups",
        "com.topjohnwu.superuser.RECEIVER_BROADCAST"
    }

    class_strings = {}
    class_superclasses = {}
    class_fields = {}
    class_methods = {}
    class_dex = {}

    with zipfile.ZipFile(apk_path, "r") as z:
        dex_files = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
        for dex_name in dex_files:
            data = z.read(dex_name)
            (string_ids_size, string_ids_off,
             type_ids_size, type_ids_off,
             proto_ids_size, proto_ids_off,
             field_ids_size, field_ids_off,
             method_ids_size, method_ids_off,
             class_defs_size, class_defs_off) = struct.unpack("<12I", data[56:104])

            string_offsets = struct.unpack(f"<{string_ids_size}I", data[string_ids_off : string_ids_off + string_ids_size * 4])

            strings_cache = {}
            def get_string(s_idx):
                if s_idx in strings_cache:
                    return strings_cache[s_idx]
                s_off = string_offsets[s_idx]
                _, off = uleb128(data, s_off)
                end = data.find(b"\x00", off)
                val = data[off:end].decode("utf-8", "ignore")
                strings_cache[s_idx] = val
                return val

            target_indices = {}
            for s_idx in range(string_ids_size):
                s_val = get_string(s_idx)
                if s_val in target_strings:
                    target_indices[s_idx] = s_val

            type_string_indices = struct.unpack(f"<{type_ids_size}I", data[type_ids_off : type_ids_off + type_ids_size * 4])
            def get_type_name(t_idx):
                if t_idx < len(type_string_indices):
                    raw = get_string(type_string_indices[t_idx])
                    if raw.startswith("L") and raw.endswith(";"):
                        return raw[1:-1].replace("/", ".")
                    return raw
                return f"type_{t_idx}"

            proto_records = []
            for p_idx in range(proto_ids_size):
                poff = proto_ids_off + p_idx * 12
                shorty_idx, ret_idx, params_off = struct.unpack("<III", data[poff : poff + 12])
                proto_records.append((ret_idx, params_off))

            def get_proto_info(p_idx):
                ret_idx, params_off = proto_records[p_idx]
                ret_type = get_type_name(ret_idx)
                param_types = []
                if params_off != 0:
                    p_size = struct.unpack("<I", data[params_off : params_off + 4])[0]
                    t_indices = struct.unpack(f"<{p_size}H", data[params_off + 4 : params_off + 4 + p_size * 2])
                    param_types = [get_type_name(ti) for ti in t_indices]
                return ret_type, param_types

            method_records = []
            for m_idx in range(method_ids_size):
                moff = method_ids_off + m_idx * 8
                c_idx, p_idx, n_idx = struct.unpack("<HHI", data[moff : moff + 8])
                method_records.append((c_idx, p_idx, n_idx))

            field_records = []
            for f_idx in range(field_ids_size):
                foff = field_ids_off + f_idx * 8
                c_idx, t_idx, n_idx = struct.unpack("<HHI", data[foff : foff + 8])
                field_records.append((c_idx, t_idx, n_idx))

            for c_idx in range(class_defs_size):
                c_off = class_defs_off + c_idx * 32
                class_idx, access_flags, superclass_idx, _, _, _, class_data_off, _ = struct.unpack("<8I", data[c_off : c_off + 32])
                if class_data_off == 0:
                    continue
                class_name = get_type_name(class_idx)
                superclass_name = get_type_name(superclass_idx) if superclass_idx != 0xFFFFFFFF else None
                class_superclasses[class_name] = superclass_name
                class_dex[class_name] = dex_name

                p = class_data_off
                static_fields_size, p = uleb128(data, p)
                instance_fields_size, p = uleb128(data, p)
                direct_methods_size, p = uleb128(data, p)
                virtual_methods_size, p = uleb128(data, p)

                f_list = []
                cur_field_idx = 0
                for _ in range(static_fields_size):
                    f_diff, p = uleb128(data, p)
                    f_flags, p = uleb128(data, p)
                    cur_field_idx += f_diff
                    fc_idx, ft_idx, fn_idx = field_records[cur_field_idx]
                    f_list.append(("static", get_type_name(ft_idx), get_string(fn_idx)))
                cur_field_idx = 0
                for _ in range(instance_fields_size):
                    f_diff, p = uleb128(data, p)
                    f_flags, p = uleb128(data, p)
                    cur_field_idx += f_diff
                    fc_idx, ft_idx, fn_idx = field_records[cur_field_idx]
                    f_list.append(("instance", get_type_name(ft_idx), get_string(fn_idx)))
                class_fields[class_name] = f_list

                m_list = []
                used_strings = set()
                for count in [direct_methods_size, virtual_methods_size]:
                    cur_method_idx = 0
                    for _ in range(count):
                        m_diff, p = uleb128(data, p)
                        m_flags, p = uleb128(data, p)
                        code_off, p = uleb128(data, p)
                        cur_method_idx += m_diff
                        mc_idx, mp_idx, mn_idx = method_records[cur_method_idx]
                        ret_type, param_types = get_proto_info(mp_idx)
                        m_list.append((m_flags, get_string(mn_idx), ret_type, param_types))

                        if code_off != 0 and target_indices:
                            insns_size = struct.unpack("<I", data[code_off + 12 : code_off + 16])[0]
                            i = code_off + 16
                            limit = i + insns_size * 2
                            while i < limit:
                                opcode = data[i]
                                if opcode == 0x1A:
                                    s_idx = struct.unpack("<H", data[i + 2 : i + 4])[0]
                                    if s_idx in target_indices:
                                        used_strings.add(target_indices[s_idx])
                                    i += 4
                                elif opcode == 0x1B:
                                    s_idx = struct.unpack("<I", data[i + 2 : i + 6])[0]
                                    if s_idx in target_indices:
                                        used_strings.add(target_indices[s_idx])
                                    i += 6
                                else:
                                    i += 2
                class_strings[class_name] = used_strings
                class_methods[class_name] = m_list

    # Match semantic invariants
    res = {}
    for c, s in class_strings.items():
        if "$" in c:
            continue

        if "org.swiftapps.swiftbackup:/oauth" in s:
            flds = class_fields.get(c, [])
            has_static_str = any(f[0] == "static" and ("String" in f[1] or "Uri" in f[1]) for f in flds)
            if has_static_str:
                res["clientId"] = f"defpackage.{c}" if not "." in c else c
            else:
                res["oauthHelper"] = f"defpackage.{c}" if not "." in c else c

        if "setup_cloud_first_startup" in s and "KEY_SCHEDULE_ENABLED" in s and "AlarmReceiver" not in c:
            res["homeViewModel"] = f"defpackage.{c}" if not "." in c else c

        if "client ID cannot be null or empty" in s:
            res["authRequestBuilder"] = f"defpackage.{c}" if not "." in c else c

        if "clearAnonymousSignIn" in s:
            mths = class_methods.get(c, [])
            for flags, name, ret, params in mths:
                if (flags & 0x8) != 0 and "MFirebaseUser" in ret and len(params) == 0:
                    res["authUser"] = f"defpackage.{c}" if not "." in c else c

        if "anonymous@swiftbackup.app" in s:
            mths = class_methods.get(c, [])
            for flags, name, ret, params in mths:
                if (flags & 0x8) != 0 and "MFirebaseUser" in ret and len(params) == 0:
                    res["anonUser"] = f"defpackage.{c}" if not "." in c else c

        if "FCW" in s and ".info/connected" in s:
            res["firebaseWatcher"] = f"defpackage.{c}" if not "." in c else c

        if "Maps with non-string keys are not supported" in s:
            res["customClassMapper"] = f"defpackage.{c}" if not "." in c else c

        if "backup_storage_location" in s and "app_backups" in s:
            res["settingsFragment"] = f"defpackage.{c}" if not "." in c else c
            sup = class_superclasses.get(c)
            if sup:
                res["baseSettingsFragment"] = f"defpackage.{sup}" if not "." in sup else sup

        if "FireSynchronizer" in s:
            mths = class_methods.get(c, [])
            for flags, name, ret, params in mths:
                if len(params) == 2 and params[1] in ("Z", "boolean") and ret not in ("V", "void"):
                    res["fireSynchronizer"] = f"defpackage.{c}" if not "." in c else c
                    res["_fireSynchronizerSuccess_base"] = ret
                    res["_fireSynchronizer_dex"] = class_dex.get(c)

        if "apkBackupDate" in s and "dataBackupDate" in s:
            res["appBackup"] = f"defpackage.{c}" if not "." in c else c

        if "dateBackupUpdated" in s and "minSBVersionCodeRequired" in s:
            res["appMetadataXml"] = f"defpackage.{c}" if not "." in c else c

        if "com.topjohnwu.superuser.RECEIVER_BROADCAST" in s:
            res["rootServiceManager"] = f"defpackage.{c}" if not "." in c else c

    # Resolve fireSynchronizerSuccess
    fs_base = res.pop("_fireSynchronizerSuccess_base", None)
    fs_dex = res.pop("_fireSynchronizer_dex", None)
    if fs_base:
        base_short = fs_base.replace("defpackage.", "")
        candidates = []
        for c, sup in class_superclasses.items():
            if sup == base_short and "$" not in c:
                name_lower = c.lower()
                if any(bad in name_lower for bad in ("error", "fail", "abort")):
                    continue
                # Check constructors
                has_throwable = False
                for m_flags, m_name, m_ret, m_params in class_methods.get(c, []):
                    if m_name == "<init>" and any("Throwable" in p or "Exception" in p for p in m_params):
                        has_throwable = True
                        break
                if not has_throwable:
                    candidates.append(c)

        # Prioritize candidates from same DEX slice as FireSynchronizer
        selected = None
        for c in candidates:
            if class_dex.get(c) == fs_dex:
                selected = c
                break
        if not selected and candidates:
            selected = candidates[0]

        if selected:
            res["fireSynchronizerSuccess"] = f"defpackage.{selected}" if not "." in selected else selected

    res["settingsDetailFragment"] = "org.swiftapps.swiftbackup.settings.a"
    return res

def format_kotlin_entry(version_code: int, classes: dict) -> str:
    """Formats resolved targets into a VersionClasses mapping block."""
    lines = [f"    {version_code} to VersionClasses("]
    keys = [
        "clientId", "homeViewModel", "authUser", "anonUser", "oauthHelper",
        "authRequestBuilder", "firebaseWatcher", "fireSynchronizer",
        "fireSynchronizerSuccess", "customClassMapper", "settingsFragment",
        "settingsDetailFragment", "baseSettingsFragment"
    ]
    for k in keys:
        if k in classes:
            lines.append(f'        {k} = "{classes[k]}",')
    lines.append("    ),")
    return "\n".join(lines)

def update_dexkit_kt(dexkit_file: str, version_code: int, entry_str: str) -> bool:
    """Inserts a new VersionClasses block into DexKit.kt versionMap."""
    with open(dexkit_file, "r", encoding="utf-8") as f:
        content = f.read()

    if f"{version_code} to VersionClasses(" in content:
        print(f"[-] VersionCode {version_code} is already in {dexkit_file}")
        return False

    # Insert before closing parenthesis of versionMap
    pattern = r"(val versionMap = mapOf\([\s\S]*?)(\n\))"
    match = re.search(pattern, content)
    if not match:
        print(f"[!] Could not locate versionMap in {dexkit_file}")
        return False

    replacement = match.group(1) + "\n" + entry_str + match.group(2)
    with open(dexkit_file, "w", encoding="utf-8") as f:
        f.write(replacement)
    print(f"[+] Updated {dexkit_file} with version {version_code}")
    return True

def update_tests(test_file: str, version_code: int, classes: dict) -> bool:
    """Adds unit test assertion for the new version in DexKitVersionMapTest.kt."""
    with open(test_file, "r", encoding="utf-8") as f:
        content = f.read()

    if f"val v{version_code} = versionMap[{version_code}]" in content:
        return False

    # 1. Add assertTrue(versionMap.containsKey(version_code))
    c_check = f"        assertTrue(versionMap.containsKey({version_code}))\n"
    target_pos = content.find("        assertTrue(versionMap.containsKey(626))")
    if target_pos != -1:
        insert_idx = content.find("\n", target_pos) + 1
        content = content[:insert_idx] + c_check + content[insert_idx:]

    # 2. Add test assertions block
    test_block = [
        f"\n        val v{version_code} = versionMap[{version_code}]",
        f"        assertNotNull(v{version_code})",
        f'        assertEquals("{classes.get("clientId", "")}", v{version_code}!!.clientId)',
        f'        assertEquals("{classes.get("homeViewModel", "")}", v{version_code}.homeViewModel)',
        f'        assertEquals("{classes.get("authUser", "")}", v{version_code}.authUser)',
        f'        assertEquals("{classes.get("anonUser", "")}", v{version_code}.anonUser)',
        f'        assertEquals("{classes.get("firebaseWatcher", "")}", v{version_code}.firebaseWatcher)',
        f'        assertEquals("{classes.get("fireSynchronizer", "")}", v{version_code}.fireSynchronizer)',
        f'        assertEquals("{classes.get("fireSynchronizerSuccess", "")}", v{version_code}.fireSynchronizerSuccess)',
        f'        assertEquals("{classes.get("settingsFragment", "")}", v{version_code}.settingsFragment)',
        f'        assertEquals("{classes.get("settingsDetailFragment", "")}", v{version_code}.settingsDetailFragment)',
        f'        assertEquals("{classes.get("baseSettingsFragment", "")}", v{version_code}.baseSettingsFragment)'
    ]
    test_str = "\n".join(test_block)

    # Insert before end of versionMapReturnsExactMappingsForKnownVersion
    last_brace = content.rfind("    }")
    if last_brace != -1:
        content = content[:last_brace] + test_str + "\n" + content[last_brace:]

    with open(test_file, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"[+] Updated unit tests in {test_file}")
    return True

def update_reverse_engineering_doc(doc_file: str, version_code: int, classes: dict):
    """Appends a new version column to docs/REVERSE_ENGINEERING.md target table."""
    if not os.path.exists(doc_file):
        return
    with open(doc_file, "r", encoding="utf-8") as f:
        content = f.read()

    marker = f"(v{version_code})"
    if marker in content:
        return

    # Replace header row to add new version column
    table_header_pat = r"(\| Logical Target \| Purpose & Responsibility (?:\| Known Classes \([^\)]+\) )+)(\| Semantic Invariant Tokens & Footprint \|)"
    m = re.search(table_header_pat, content)
    if m:
        content = content[:m.start()] + m.group(1) + f"| Known Classes (v{version_code}) " + m.group(2) + content[m.end():]
        sep_pat = r"(\| :--- \| :--- (?:\| :--- )+)(\| :--- \|)"
        m_sep = re.search(sep_pat, content)
        if m_sep:
            content = content[:m_sep.start()] + m_sep.group(1) + "| :--- " + m_sep.group(2) + content[m_sep.end():]

        row_map = {
            "vClass": "common.V",
            "SwiftApp": "org.swiftapps.swiftbackup.SwiftApp",
            "homeViewModelClass": classes.get("homeViewModel", "-"),
            "clientIdClass": classes.get("clientId", "-"),
            "oauthHelperClass": classes.get("oauthHelper", "-"),
            "authRequestBuilderClass": classes.get("authRequestBuilder", "-"),
            "authUserClass": classes.get("authUser", "-"),
            "anonUserClass": classes.get("anonUser", "-"),
            "firebaseWatcherClass": classes.get("firebaseWatcher", "-"),
            "fireSynchronizerClass": classes.get("fireSynchronizer", "-"),
            "fireSynchronizerSuccessClass": classes.get("fireSynchronizerSuccess", "-"),
            "fireSynchronizerWriteSuccessClass": classes.get("fireSynchronizerWriteSuccess", classes.get("fireSynchronizerSuccess", "-")),
            "customClassMapperClass": classes.get("customClassMapper", "-"),
            "settingsFragmentClass": classes.get("settingsFragment", "-"),
            "baseSettingsFragmentClass": classes.get("baseSettingsFragment", "-"),
            "libnative-lib.so": "Native library",
        }

        for target_key, class_val in row_map.items():
            row_pat = re.compile(r"(\| \*\*`" + re.escape(target_key) + r"`\*\* \| [^\|]+ (?:\| [^\|]+ )+)(\| [^\|]+ \|)")
            m_row = row_pat.search(content)
            if m_row:
                replacement_cell = f"`{class_val}`" if not class_val.startswith("Native") and class_val != "-" else class_val
                content = content[:m_row.start()] + m_row.group(1) + f"| {replacement_cell} " + m_row.group(2) + content[m_row.end():]

        with open(doc_file, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"[+] Updated target table in {doc_file}")

def main():
    parser = argparse.ArgumentParser(description="Scan Swift Backup APK for invariant targets.")
    parser.add_argument("--apk", required=True, help="Path to Swift Backup APK")
    parser.add_argument("--update-code", action="store_true", help="Automatically inject mapping into DexKit.kt, tests, and docs")
    args = parser.parse_args()

    if not os.path.exists(args.apk):
        print(f"Error: APK file not found at {args.apk}")
        sys.exit(1)

    print(f"[*] Parsing metadata for {args.apk}...")
    vcode, vname = parse_manifest(args.apk)
    print(f"[*] Detected Swift Backup: v{vname} (versionCode {vcode})")

    print(f"[*] Scanning DEX bytecode for semantic invariants...")
    classes = scan_apk(args.apk)
    print(f"[+] Successfully resolved {len(classes)} target classes:")
    for k, v in sorted(classes.items()):
        print(f"    {k:25}: {v}")

    entry_str = format_kotlin_entry(vcode, classes)
    print("\nGenerated Kotlin Mapping:")
    print(entry_str)

    if args.update_code:
        repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        dexkit_kt = os.path.join(repo_root, "app/src/main/java/io/github/s1ddhants1/swiftbackupprem/DexKit.kt")
        test_kt = os.path.join(repo_root, "app/src/test/java/io/github/s1ddhants1/swiftbackupprem/DexKitVersionMapTest.kt")
        doc_file = os.path.join(repo_root, "docs/REVERSE_ENGINEERING.md")
        update_dexkit_kt(dexkit_kt, vcode, entry_str)
        update_tests(test_kt, vcode, classes)
        update_reverse_engineering_doc(doc_file, vcode, classes)

    if "GITHUB_OUTPUT" in os.environ and vcode:
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"version_code={vcode}\n")
            f.write(f"version_name={vname}\n")
            f.write(f"doc_title={os.path.basename(args.apk)}\n")

if __name__ == "__main__":
    main()
