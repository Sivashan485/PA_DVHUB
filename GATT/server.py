#!/usr/bin/env python3
from bluezero import peripheral
from gi.repository import GLib
import json
import argparse
import subprocess
import time
from typing import Optional, Tuple

SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"
WIFI_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef2"
HUB_INFO_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef3"
AUTH_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef4"
WIFI_STATUS_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef5"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adapter", required=True, help="Adapter MAC address")
    parser.add_argument("--name", default="SMARTTUPPLEWARE_HUB", help="BLE local name")
    parser.add_argument("--device-id", required=True, help="Unique hub identifier (e.g., hub-001)")
    parser.add_argument("--fw", default="1.0.0", help="Firmware version string")
    parser.add_argument("--auth-token", default="pair-token-123", help="Simple auth token (demo)")
    # optionally pin Wi-Fi interface (recommended on multi-adapter systems)
    parser.add_argument(
        "--wifi-iface",
        default="",
        help="Wi-Fi interface (e.g., wlan0, wlp2s0). If empty, auto-detect.",
    )
    args = parser.parse_args()

    # ---- Hub state ----
    value = bytearray(b"ZHAW-SMARTTUPPLEWARE")
    wifi_credentials = {"ssid": None, "password": None}
    wifi_status = {"success": None, "reason": "not attempted"}
    is_authorized = False

    HUB_IDENTITY = {
        "type": "SMARTTUPPLEWARE_HUB",
        "vendor": "ZHAW",
        "model": "DVHUB",
        "fw": args.fw,
        "device_id": args.device_id,
    }

    # ---- Helpers ----
    def _run(cmd, timeout=30):
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)

    def _detect_wifi_iface() -> Optional[str]:
        """
        Detect a Wi-Fi interface managed by NetworkManager.
        Prefers a connected Wi-Fi device; otherwise picks the first Wi-Fi device.
        """
        try:
            res = _run(["nmcli", "-t", "-f", "DEVICE,TYPE,STATE", "dev"], timeout=10)
            if res.returncode != 0:
                return None
            wifi_devs = []
            for ln in (res.stdout or "").splitlines():
                parts = ln.split(":")
                if len(parts) >= 3 and parts[1] == "wifi":
                    wifi_devs.append((parts[0], parts[2]))
            if not wifi_devs:
                return None
            for dev, st in wifi_devs:
                if st == "connected":
                    return dev
            return wifi_devs[0][0]
        except Exception:
            return None

    def forget_saved_wifi_profiles(ssid: str) -> int:
        """
        "Forget" Wi-Fi networks by deleting ALL saved NetworkManager connection
        profiles whose stored SSID matches `ssid`.

        Returns number of deleted profiles.
        """
        if not ssid:
            return 0

        # List saved connections with their name, uuid, and type
        res = _run(["nmcli", "-t", "-f", "NAME,UUID,TYPE", "con", "show"], timeout=10)
        if res.returncode != 0:
            return 0

        deleted = 0
        for ln in (res.stdout or "").splitlines():
            try:
                name, uuid, ctype = ln.split(":", 2)
            except ValueError:
                continue

            # Only consider Wi-Fi connections
            if ctype != "802-11-wireless":
                continue

            # Query the SSID stored in that profile
            ss = _run(["nmcli", "-g", "802-11-wireless.ssid", "con", "show", uuid], timeout=10)
            prof_ssid = (ss.stdout or "").strip()

            if prof_ssid == ssid:
                _run(["nmcli", "con", "delete", uuid], timeout=10)
                deleted += 1

        return deleted

    def try_connect_wifi(
        ssid: str,
        password: str,
        wifi_iface: Optional[str] = None,
        timeout_s: int = 35,
    ) -> Tuple[bool, str]:
        """
        Connect using NetworkManager (nmcli).

        Features:
          - Ensures Wi-Fi radio is on
          - Pins to a specific Wi-Fi interface (auto-detected if not provided)
          - Forces a rescan before connecting
          - NEW: "forgets" any existing saved profile(s) with the same SSID
          - Falls back to creating a connection profile (helps hidden SSIDs / scan issues)

        Returns (success, reason).
        """
        if not ssid:
            return False, "missing ssid"
        if password is None:
            password = ""

        # Choose interface
        if not wifi_iface:
            wifi_iface = _detect_wifi_iface()
        if not wifi_iface:
            return False, "no wifi interface found (nmcli dev shows no TYPE=wifi)"

        # Turn Wi-Fi on
        try:
            _run(["nmcli", "radio", "wifi", "on"], timeout=10)
        except Exception:
            pass

        # Rescan
        try:
            _run(["nmcli", "dev", "wifi", "rescan", "ifname", wifi_iface], timeout=15)
            time.sleep(2)
        except Exception:
            pass

        # Forget old saved profiles for this SSID (prevents stale creds/settings)
        try:
            n = forget_saved_wifi_profiles(ssid)
            if n:
                print(f"Forgot {n} saved profile(s) for SSID={ssid}", flush=True)
        except Exception as e:
            # Don't fail provisioning just because cleanup failed
            print(f"Warning: failed to forget profiles for SSID={ssid}: {e}", flush=True)

        # Best-effort visibility check
        visible = False
        try:
            lst = _run(
                ["nmcli", "-t", "-f", "SSID", "dev", "wifi", "list", "ifname", wifi_iface],
                timeout=15,
            )
            if lst.returncode == 0:
                visible = ssid in (lst.stdout or "").splitlines()
        except Exception:
            visible = False

        # Attempt direct connect first
        cmd = ["nmcli", "dev", "wifi", "connect", ssid, "ifname", wifi_iface]
        if password != "":
            cmd += ["password", password]

        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout_s)
        except subprocess.TimeoutExpired:
            return False, "timeout"
        except FileNotFoundError:
            return False, "nmcli not found (NetworkManager not installed)"
        except Exception as e:
            return False, f"exception: {e}"

        if res.returncode == 0:
            return True, f"connected via {wifi_iface}"

        msg = (res.stderr or res.stdout or "nmcli connect failed").strip()

        # If not found / not visible -> fallback: create explicit profile (supports hidden SSIDs)
        if ("No network with SSID" in msg) or (not visible):
            con_name = f"bleprov-{ssid}"

            # Delete if exists (ignore errors)
            try:
                _run(["nmcli", "con", "delete", con_name], timeout=10)
            except Exception:
                pass

            add = _run(
                ["nmcli", "con", "add", "type", "wifi", "ifname", wifi_iface, "con-name", con_name, "ssid", ssid],
                timeout=15,
            )
            if add.returncode != 0:
                return False, (add.stderr or add.stdout or msg).strip()

            # Mark hidden (safe even if not hidden)
            _run(["nmcli", "con", "modify", con_name, "wifi.hidden", "yes"], timeout=10)

            # Security
            if password != "":
                _run(["nmcli", "con", "modify", con_name, "wifi-sec.key-mgmt", "wpa-psk"], timeout=10)
                _run(["nmcli", "con", "modify", con_name, "wifi-sec.psk", password], timeout=10)

            up = _run(["nmcli", "con", "up", con_name], timeout=30)
            if up.returncode == 0:
                return True, f"connected (profile) via {wifi_iface}"

            return False, (up.stderr or up.stdout or msg).strip()

        return False, msg

    # ---- Callbacks ----
    def read_value():
        return list(value)

    def write_value(val, options):
        nonlocal value
        value = bytearray(val)

        print("\n=== GENERIC WRITE RECEIVED ===", flush=True)
        print("Options:", options, flush=True)
        print("Raw list:", val, flush=True)
        b = bytes(val)
        print("Length:", len(b), flush=True)
        print("Hex:", b.hex(), flush=True)
        print("Decoded:", b.decode("utf-8", errors="replace"), flush=True)

    def read_hub_info():
        payload = json.dumps(HUB_IDENTITY).encode("utf-8")
        return list(payload)

    def write_auth(val, options):
        nonlocal is_authorized
        b = bytes(val)

        print("\n=== AUTH WRITE RECEIVED ===", flush=True)
        print("Options:", options, flush=True)
        print("Length:", len(b), flush=True)
        print("Hex:", b.hex(), flush=True)
        print("Decoded:", b.decode("utf-8", errors="replace"), flush=True)

        token = b.decode("utf-8", errors="replace").strip()
        is_authorized = (token == args.auth_token)
        print("Expected token:", args.auth_token, flush=True)
        print("Authorized:", is_authorized, flush=True)

    def write_wifi_credentials(val, options):
        b = bytes(val)

        print("\n=== WIFI WRITE RECEIVED ===", flush=True)
        print("Options:", options, flush=True)
        print("Length:", len(b), flush=True)
        print("Hex:", b.hex(), flush=True)
        print("Decoded:", b.decode("utf-8", errors="replace"), flush=True)

        if not is_authorized:
            print("Rejecting WiFi write: not authorized (write AUTH first)", flush=True)
            wifi_status["success"] = False
            wifi_status["reason"] = "not authorized"
            return

        try:
            data = json.loads(b.decode("utf-8"))
            wifi_credentials["ssid"] = data.get("ssid")
            wifi_credentials["password"] = data.get("password")

            print("Parsed WiFi credentials:", flush=True)
            print("  SSID:", wifi_credentials["ssid"], flush=True)
            print("  PASS:", wifi_credentials["password"], flush=True)

            wifi_iface = args.wifi_iface.strip() or None

            # Try to connect immediately
            ok, reason = try_connect_wifi(
                wifi_credentials["ssid"],
                wifi_credentials["password"],
                wifi_iface=wifi_iface,
                timeout_s=35,
            )
            wifi_status["success"] = ok
            wifi_status["reason"] = reason

            print("WiFi connect result:", wifi_status, flush=True)

        except Exception as e:
            print("Invalid WiFi JSON:", e, flush=True)
            wifi_status["success"] = False
            wifi_status["reason"] = f"invalid json: {e}"

    def read_wifi_status():
        payload = json.dumps(wifi_status).encode("utf-8")
        return list(payload)

    # ---- BLE Peripheral ----
    ble = peripheral.Peripheral(
        adapter_address=args.adapter,
        local_name=args.name,
        appearance=0x0000,
    )

    def on_connect(dev):
        print(f"\n>>> CENTRAL CONNECTED: {getattr(dev, 'address', dev)}", flush=True)

    def on_disconnect(adapter_addr=None, device_addr=None):
        print(f"\n<<< CENTRAL DISCONNECTED: {device_addr}", flush=True)

    ble.on_connect = on_connect
    ble.on_disconnect = on_disconnect

    ble.add_service(srv_id=1, uuid=SERVICE_UUID, primary=True)

    # Generic read/write characteristic
    ble.add_characteristic(
        srv_id=1,
        chr_id=1,
        uuid=CHAR_UUID,
        value=list(value),
        notifying=False,
        flags=["read", "write", "write-without-response"],
        read_callback=read_value,
        write_callback=write_value,
    )

    # Hub info characteristic (read-only)
    ble.add_characteristic(
        srv_id=1,
        chr_id=3,
        uuid=HUB_INFO_CHAR_UUID,
        value=read_hub_info(),
        notifying=False,
        flags=["read"],
        read_callback=read_hub_info,
    )

    # Auth characteristic (write-only)
    ble.add_characteristic(
        srv_id=1,
        chr_id=4,
        uuid=AUTH_CHAR_UUID,
        value=[],
        notifying=False,
        flags=["write", "write-without-response"],
        write_callback=write_auth,
    )

    # WiFi credentials characteristic (write-only)
    ble.add_characteristic(
        srv_id=1,
        chr_id=2,
        uuid=WIFI_CHAR_UUID,
        value=[],
        notifying=False,
        flags=["write", "write-without-response"],
        write_callback=write_wifi_credentials,
    )

    # WiFi status characteristic (read-only)
    ble.add_characteristic(
        srv_id=1,
        chr_id=5,
        uuid=WIFI_STATUS_CHAR_UUID,
        value=read_wifi_status(),
        notifying=False,
        flags=["read"],
        read_callback=read_wifi_status,
    )

    print("Publishing hub:", HUB_IDENTITY, "name:", args.name, flush=True)
    print("Wi-Fi interface:", (args.wifi_iface.strip() or "auto-detect"), flush=True)
    ble.publish()
    print("Ready. In nRF Connect: CONNECT -> write AUTH -> write WIFI -> read WIFI_STATUS", flush=True)

    GLib.MainLoop().run()


if __name__ == "__main__":
    main()

