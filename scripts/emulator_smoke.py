#!/usr/bin/env python3
# /// script
# dependencies = ["grpcio==1.74.0", "grpcio-tools==1.74.0"]
# ///
"""Separate-UID UI/IPC smoke with the real emulator microphone and fixture backend.

Run with uv run scripts/emulator_smoke.py --sdk "$ANDROID_HOME" --discovery PATH.
The emulator must use -grpc-use-token and the integration APK must be installed.
"""
import argparse
import importlib
import math
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import grpc
from grpc_tools import protoc
import grpc_tools
from concurrent.futures import ThreadPoolExecutor

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", required=True)
parser.add_argument("--discovery", required=True)
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--configured", action="store_true")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    raise SystemExit("This test only accepts emulator serials")

def adb(*cmd):
    return subprocess.check_output(["adb", "-s", args.serial, *cmd], text=True, timeout=30)

def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/dictate-test.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/dictate-test.xml"))

def node(text, timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        tree = ui()
        for item in tree.iter("node"):
            if text.casefold() in item.get("text", "").casefold() or text == item.get("resource-id", "") or text == item.get("class", ""):
                return item
    raise AssertionError("UI element not found: " + text)

def tap(text):
    for attempt in range(5):
        try:
            n = node(text, 4)
            break
        except AssertionError:
            if attempt == 4: raise
            adb("shell", "input", "swipe", "540", "1900", "540", "700", "400")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

def permit_microphone():
    tree = ui()
    for item in tree.iter("node"):
        if item.get("resource-id", "").endswith("permission_allow_foreground_only_button"):
            x1,y1,x2,y2 = map(int,re.findall(r"\d+", item.attrib["bounds"]))
            adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
            return

config = dict(line.strip().split("=",1) for line in Path(args.discovery).read_text().splitlines()
              if "=" in line)
token = config["grpc.token"]
port = config.get("grpc.port", "8554")
with tempfile.TemporaryDirectory(prefix="dictate-proto-") as directory:
    proto_dir = Path(args.sdk) / "emulator/lib"
    result = protoc.main(["protoc", "-I"+str(proto_dir),
                          "-I"+str(Path(grpc_tools.__file__).parent / "_proto"),
                          "--python_out="+directory, "--grpc_python_out="+directory,
                          str(proto_dir / "emulator_controller.proto")])
    assert result == 0
    sys.path.insert(0, directory)
    pb = importlib.import_module("emulator_controller_pb2")
    rpc = importlib.import_module("emulator_controller_pb2_grpc")
    channel = grpc.insecure_channel("127.0.0.1:"+port)
    grpc.channel_ready_future(channel).result(timeout=10)
    client = rpc.EmulatorControllerStub(channel)

    def inject():
        def packets():
            fmt = pb.AudioFormat(samplingRate=48000, channels=0, format=1, mode=1)
            start = time.monotonic()
            for frame in range(300):
                audio = b"".join(struct.pack("<h", int(10000*math.sin(2*math.pi*440*i/48000)))
                                 for i in range(frame*960,(frame+1)*960))
                yield pb.AudioPacket(format=fmt, audio=audio, timestamp=int(time.time()*1000000))
                remaining = start+(frame+1)*0.02-time.monotonic()
                if remaining > 0: time.sleep(remaining)  # pacing audio, not test synchronization
        client.injectAudio(packets(), metadata=[("authorization","Bearer "+token)], timeout=10)

    if not args.configured:
        adb("shell", "am", "start", "-n", "io.github.ev0lv3nta.dictate/.SettingsActivity")
        tap("Нужен микрофон")
        permit_microphone()
        adb("shell", "am", "start", "-n", "io.github.ev0lv3nta.dictate.sample/.MainActivity")
        tap("Start")
        permit_microphone()
        tap("Start")
        node("error 9")
        print("PASS: unapproved external UID denied")

        adb("shell", "am", "start", "-n", "io.github.ev0lv3nta.dictate/.SettingsActivity")
        tap("Разрешённые приложения")
        tap("android.widget.EditText")
        adb("shell", "input", "text", "io.github.ev0lv3nta.dictate.sample")
        tap("Сохранить")
        tap("Разрешить индикатор записи")
        tap("Dictate")
        tap("Allow display over other apps")
    adb("shell", "am", "start", "-n", "io.github.ev0lv3nta.dictate.sample/.MainActivity")
    tap("Start")
    node("ready")
    inject()
    node("Fixture: microphone captured")
    print("PASS: real AudioRecord, external UID, fixture transcript")
    tap("Start")
    node("ready")
    tap("Cancel")
    tap("Start")
    node("ready")
    tap("Stop")
    node("error")
    print("PASS: cancel/restart and no speech")
