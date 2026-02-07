#!/usr/bin/env python3
from bluezero import peripheral
from gi.repository import GLib
import json
import argparse
import subprocess
import time

SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"
WIFI_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef2"
HUB_INFO_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef3"
AUTH_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef4"
WIFI_STATUS_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef5"  # NEW


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adapter", required=True, help="Adapter MAC address")
    parser.add_argument("--name", default="SMARTTUPPLEWARE_HUB", help="BLE local name")
    parser.add_argument("--device-id", required=True, help="Unique hub identifier (e.g., hub-001)")
    parser.add_argument("--fw", default="1.0.0", help="Firmware version string")
    parser.add_argument("--auth-token", default="pair-token-123", help="Simple auth token (demo)")
    args = parser.parse_args()

    # ---- Hub state ----
    value = bytearray(b"ZHAW-SMARTTUPPLEWARE")
    wifi_credentials = {"ssid": None, "password": None}
    wifi_status = {"success": None, "reason": "not attempted"}  # NEW
    is_authorized = False

    HUB_IDENTITY = {
        "type": "SMARTTUPPLEWARE_HUB",
        "vendor": "ZHAW",
        "model": "DVHUB",
        "fw": args.fw,
        "device_id": args.device_id,
    }

    # ---- Helpers ----
    def try_connect_wifi(ssid: str, password: str, wifi_iface: str | None = None, timeout_s: int = 35):
    """
    Connect via NetworkManager (nmcli).
    - Rescans first (avoids 'SSID not found' from stale cache)
    - Optionally pins to a specific Wi-Fi interface
    - Falls back to creating a connection (helps with hidden SSIDs)
    Returns (success: bool, reason: str).
    """
    if not ssid:
        return False, "missing ssid"
    if password is None:
        password = ""

    def run(cmd, t=timeout_s):
        return subprocess.run(cmd, capture_output=True, text=True, timeout=t)

    # Identify wifi interface if not provided
    if wifi_iface is None:
        devs = run(["nmcli", "-t", "-f", "DEVICE,TYPE,STATE", "dev"])
        wifi_devs = []
        for ln in (devs.stdout or "").splitlines():
            parts = ln.split(":")
            if len(parts) >= 3 and parts[1] == "wifi":
                wifi_devs.append((parts[0], parts[2]))
        # Prefer a connected one, else first wifi device
        wifi_iface = next((d for d, st in wifi_devs if st == "connected"), None) or (wifi_devs[0][0] if wifi_devs else None)

    if not wifi_iface:
        return False, "no wifi interface found (nmcli dev shows no TYPE=wifi)"

    # Ensure wifi radio is on
    run(["nmcli", "radio", "wifi", "on"], t=10)

    # Rescan + short wait
    run(["nmcli", "dev", "wifi", "rescan", "ifname", wifi_iface], t=15)
    time.sleep(2)

    # Check if SSID is visible (best effort)
    lst = run(["nmcli", "-t", "-f", "SSID", "dev", "wifi", "list", "ifname", wifi_iface], t=15)
    visible = ssid in (lst.stdout or "").splitlines()

    # Try normal connect first (works for visible SSIDs)
    cmd = ["nmcli", "dev", "wifi", "connect", ssid, "ifname", wifi_iface]
    if password != "":
        cmd += ["password", password]

    res = run(cmd)
    if res.returncode == 0:
        return True, "connected"

    msg = (res.stderr or res.stdout or "nmcli connect failed").strip()

    # If SSID wasn't visible, try a hidden-SSID style connection profile
    # (also helps when scan caching is weird)
    if ("No network with SSID" in msg) or (not visible):
        con_name = f"bleprov-{ssid}"
        # delete existing with same name (ignore errors)
        run(["nmcli", "con", "delete", con_name], t=10)

        add = run(["nmcli", "con", "add",
                   "type", "wifi",
                   "ifname", wifi_iface,
                   "con-name", con_name,
                   "ssid", ssid], t=15)
        if add.returncode != 0:
            return False, (add.stderr or add.stdout or msg).strip()

        run(["nmcli", "con", "modify", con_name, "wifi.hidden", "yes"], t=10)

        if password != "":
            run(["nmcli", "con", "modify", con_name, "wifi-sec.key-mgmt", "wpa-psk"], t=10)
            run(["nmcli", "con", "modify", con_name, "wifi-sec.psk", password], t=10)

        up = run(["nmcli", "con", "up", con_name], t=30)
        if up.returncode == 0:
            return True, "connected (profile)"

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

            # Try to connect immediately
            ok, reason = try_connect_wifi(wifi_credentials["ssid"], wifi_credentials["password"])
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

    # WiFi status characteristic (read-only)  <-- NEW
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
    ble.publish()
    print(
        "Ready. In nRF Connect: CONNECT -> write AUTH -> write WIFI -> read WIFI_STATUS",
        flush=True,
    )

    GLib.MainLoop().run()


if __name__ == "__main__":
    main()

#sudo nano /etc/systemd/system/ble-hub.service


