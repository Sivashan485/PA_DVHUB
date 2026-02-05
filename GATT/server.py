#!/usr/bin/env python3
from bluezero import peripheral
from gi.repository import GLib
import json
import argparse

SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1"
WIFI_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef2"
HUB_INFO_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef3"
AUTH_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef4"


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
    is_authorized = False

    HUB_IDENTITY = {
        "type": "SMARTTUPPLEWARE_HUB",
        "vendor": "ZHAW",
        "model": "DVHUB",
        "fw": args.fw,
        "device_id": args.device_id,
    }

    # ---- Callbacks ----
    def read_value():
        # bluezero expects list of ints
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
            return

        try:
            data = json.loads(b.decode("utf-8"))
            wifi_credentials["ssid"] = data.get("ssid")
            wifi_credentials["password"] = data.get("password")

            print("Parsed WiFi credentials:", flush=True)
            print("  SSID:", wifi_credentials["ssid"], flush=True)
            print("  PASS:", wifi_credentials["password"], flush=True)
        except Exception as e:
            print("Invalid WiFi JSON:", e, flush=True)

    # ---- BLE Peripheral ----
    ble = peripheral.Peripheral(
        adapter_address=args.adapter,
        local_name=args.name,
        appearance=0x0000,
    )

    # Connection prints (these DO work with correct bluezero usage)
    def on_connect(dev):
        print(f"\n>>> CENTRAL CONNECTED: {getattr(dev, 'address', dev)}", flush=True)

    def on_disconnect(adapter_addr=None, device_addr=None):
        print(f"\n<<< CENTRAL DISCONNECTED: {device_addr}", flush=True)

    ble.on_connect = on_connect
    ble.on_disconnect = on_disconnect

    ble.add_service(srv_id=1, uuid=SERVICE_UUID, primary=True)

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

    ble.add_characteristic(
        srv_id=1,
        chr_id=3,
        uuid=HUB_INFO_CHAR_UUID,
        value=read_hub_info(),
        notifying=False,
        flags=["read"],
        read_callback=read_hub_info,
    )

    ble.add_characteristic(
        srv_id=1,
        chr_id=4,
        uuid=AUTH_CHAR_UUID,
        value=[],
        notifying=False,
        flags=["write", "write-without-response"],
        write_callback=write_auth,
    )

    ble.add_characteristic(
        srv_id=1,
        chr_id=2,
        uuid=WIFI_CHAR_UUID,
        value=[],
        notifying=False,
        flags=["write", "write-without-response"],
        write_callback=write_wifi_credentials,
    )

    print("Publishing hub:", HUB_IDENTITY, "name:", args.name, flush=True)
    ble.publish()
    print("Ready. In nRF Connect: CONNECT -> write AUTH -> write WIFI", flush=True)

    # Run the DBus/GLib event loop so callbacks fire
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
