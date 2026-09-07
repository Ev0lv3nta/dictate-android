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
import queue
import threading
import json
import atexit

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", required=True)
parser.add_argument("--discovery")
parser.add_argument("--screenshots")
parser.add_argument("--serial", default="emulator-5554")
parser.add_argument("--configured", action="store_true")
parser.add_argument("--release-check", action="store_true")
parser.add_argument("--upgrade-apk", help="Signed higher-version APK; used only with --release-check")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    raise SystemExit("This test only accepts emulator serials")

def adb(*cmd):
    return subprocess.check_output(["adb", "-s", args.serial, *cmd], text=True, timeout=30)

def ui():
    end=time.monotonic()+15
    while time.monotonic()<end:
        try:
            adb("shell", "uiautomator", "dump", "/data/local/tmp/dictate-test.xml")
            tree=ET.fromstring(adb("shell", "cat", "/data/local/tmp/dictate-test.xml"))
            # Fresh Google images can show a launcher ANR during initial setup.
            # Dismiss only that system app; a Dictate ANR remains a test failure.
            if any(n.get("text", "") == "Pixel Launcher isn't responding" for n in tree.iter("node")):
                close=next(n for n in tree.iter("node") if n.get("text", "") == "Close app")
                x1,y1,x2,y2=map(int,re.findall(r"\d+",close.attrib["bounds"]))
                adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
                print("Dismissed Pixel Launcher startup ANR; Dictate ANRs are not suppressed")
                continue
            return tree
        except (subprocess.CalledProcessError, ET.ParseError):
            continue
    raise AssertionError("uiautomator could not read the current window")

def node(text, timeout=20):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        tree = ui()
        for item in tree.iter("node"):
            if any((option[1:].casefold() == item.get("text","").casefold()) if option.startswith("=")
                   else option.casefold() in item.get("text", "").casefold() for option in text.split("|")) or text == item.get("resource-id", "") or text == item.get("class", "") or text == item.get("content-desc", ""):
                return item
    visible=[n.get("text") for n in tree.iter("node") if n.get("text")]
    print("Last test screen:", visible)
    print(adb("logcat","-d","-s","DictateSpeech","DictateIndicator","DictateFixture"))
    raise AssertionError("UI element not found: " + text)

def tap(text, checked=None):
    for attempt in range(5):
        try:
            n = node(text, 4)
            break
        except AssertionError:
            if attempt == 4: raise
            adb("shell", "input", "swipe", "540", "1900", "540", "700", "400")
    if checked is not None and n.get("checked") == str(checked).lower(): return
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

def permit_microphone():
    end=time.monotonic()+15
    while time.monotonic()<end:
        tree = ui()
        for item in tree.iter("node"):
            if item.get("resource-id", "").endswith(("permission_allow_foreground_only_button", "permission_allow_button")):
                x1,y1,x2,y2 = map(int,re.findall(r"\d+", item.attrib["bounds"]))
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                return
    raise AssertionError("Runtime microphone permission dialog did not appear")

events=queue.Queue()
event_log=subprocess.Popen(["adb","-s",args.serial,"logcat","-T","1","-v","raw","-s","DictateSample:I","*:S"],
                           stdout=subprocess.PIPE,text=True)
def read_events():
    for line in event_log.stdout: events.put(line.strip())
threading.Thread(target=read_events,daemon=True).start()
atexit.register(event_log.terminate)

def clear_events():
    while True:
        try: events.get_nowait()
        except queue.Empty: return

def wait_event(expected):
    end=time.monotonic()+12
    while time.monotonic()<end:
        try: line=events.get(timeout=max(0.1,end-time.monotonic()))
        except queue.Empty: break
        if line==expected: return
        if line.startswith("error "): raise AssertionError("Speech client: "+line)
    raise AssertionError("Speech event missing: "+expected)

if not args.discovery:
    roots = [Path.home()/"Library/Caches/TemporaryItems/avd/running",
             Path(os.environ.get("XDG_RUNTIME_DIR", "/run/user/"+str(os.getuid())))/"avd/running",
             Path("/tmp/android-"+os.environ.get("USER", "runner"))/"avd/running"]
    candidates = [p for root in roots for p in root.glob("pid_*.ini")]
    if not candidates: raise SystemExit("No emulator discovery file; pass --discovery")
    args.discovery = str(max(candidates, key=lambda p: p.stat().st_mtime))
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

    if args.release_check:
        adb("shell","am","start","-W","--activity-clear-top","-n","io.github.ev0lv3nta.dictate/.HomeActivity")
        tap("=Settings and client access")
        tap("API key · add or replace")
        tap("android.widget.EditText")
        adb("shell","input","text","invalid-local-update-fixture")
        tap("Save")
        node("ture")  # only the masked suffix, not plaintext, is visible
        permissions=adb("shell","dumpsys","package","io.github.ev0lv3nta.dictate")
        if "android.permission.RECORD_AUDIO: granted=true" not in permissions:
            tap("Microphone permission required")
            permit_microphone()
        tap("Save recent recordings on this device", checked=True)
        adb("shell","am","start","-W","--activity-clear-top","-n","io.github.ev0lv3nta.dictate/.HomeActivity")
        tap("=History")
        tap("io.github.ev0lv3nta.dictate:id/record")
        node("Recording — tap to stop")
        inject()
        tap("io.github.ev0lv3nta.dictate:id/record")
        node("Recording saved")
        node("io.github.ev0lv3nta.dictate:id/play")
        if args.upgrade_apk:
            adb("install","-r",args.upgrade_apk)
            adb("shell","am","start","-W","--activity-clear-top","-n","io.github.ev0lv3nta.dictate/.SettingsActivity")
            node("ture")
            adb("shell","am","start","-W","--activity-clear-top","-n","io.github.ev0lv3nta.dictate/.HomeActivity")
            tap("=History")
            node("io.github.ev0lv3nta.dictate:id/play")
            print("PASS: signed update preserved encrypted key, settings and audio")
        if args.screenshots:
            Path(args.screenshots).mkdir(parents=True,exist_ok=True)
            (Path(args.screenshots)/"release-recorder.png").write_bytes(subprocess.check_output(
                ["adb","-s",args.serial,"exec-out","screencap","-p"]))
        print("PASS: signed release launch, key save and real microphone; no provider calls")
        sys.exit(0)

    if not args.configured:
        adb("shell", "am", "start", "-W", "--activity-clear-top", "-n", "io.github.ev0lv3nta.dictate/.SettingsActivity")
        tap("Нужен микрофон|Microphone permission required")
        permit_microphone()
        adb("shell", "am", "start", "-W", "--activity-clear-top", "-n", "io.github.ev0lv3nta.dictate.sample/.MainActivity")
        tap("Start")
        permit_microphone()
        tap("Start")
        node("error 9")
        print("PASS: unapproved external UID denied")

        adb("shell", "am", "start", "-W", "--activity-clear-top", "-n", "io.github.ev0lv3nta.dictate/.SettingsActivity")
        tap("Разрешённые приложения|Allowed apps")
        tap("android.widget.EditText")
        adb("shell", "input", "text", "io.github.ev0lv3nta.dictate.sample")
        tap("Сохранить|Save")
        tap("Разрешить индикатор записи|Allow recording indicator")
        tap("Dictate")
        tap("Allow display over other apps")
    # A recently visible settings Activity can mask background microphone failures.
    adb("shell", "am", "force-stop", "io.github.ev0lv3nta.dictate")
    adb("shell", "am", "force-stop", "io.github.ev0lv3nta.dictate.sample")
    adb("shell", "am", "start", "-W", "--activity-clear-top", "-n", "io.github.ev0lv3nta.dictate.sample/.MainActivity")
    clear_events()
    tap("Start")
    wait_event("ready")
    wait_event("audio stream")
    inject()
    node("Fixture: microphone captured")
    print("PASS: real AudioRecord, external UID, fixture transcript")
    if args.screenshots:
        Path(args.screenshots).mkdir(parents=True, exist_ok=True)
        screenshot = subprocess.check_output(["adb", "-s", args.serial, "exec-out", "screencap", "-p"])
        (Path(args.screenshots)/"sample-fixture.png").write_bytes(screenshot)
    clear_events()
    tap("Start")
    wait_event("ready")
    tap("Cancel")
    clear_events()
    tap("Start")
    wait_event("ready")
    tap("Stop")
    wait_event("error 6")
    print("PASS: cancel/restart and no speech")
    if args.screenshots:
        report = {"api": adb("shell","getprop","ro.build.version.sdk").strip(),
                  "variant": "integration", "backend": "fixture", "audio": "generated 440 Hz PCM",
                  "commit": subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip(),
                  "checks": ["unapproved UID", "cold service microphone", "cancel/restart", "no speech"],
                  "live_api": "not run — credentials not provided"}
        (Path(args.screenshots)/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
