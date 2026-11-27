from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[2]
QUESTXR_DIR = REPO / "app/src/main/cpp/questxr"
QUEST_XR = QUESTXR_DIR / "quest_xr.cpp"
MANIFEST = REPO / "app/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"

STALL_CPP = r"""
#include <cassert>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "xr_stall.h"

static void test_schedule() {
    XrStallSchedule schedule;
    assert(!schedule.ShouldReport(0));
    assert(!schedule.ShouldReport(1999));
    assert(schedule.ShouldReport(2000));
    assert(!schedule.ShouldReport(2500));
    assert(!schedule.ShouldReport(4999));
    assert(schedule.ShouldReport(5200));
    assert(!schedule.ShouldReport(9999));
    assert(schedule.ShouldReport(10400));
    assert(!schedule.ShouldReport(19999));
    assert(schedule.ShouldReport(20000));
    assert(!schedule.ShouldReport(29000));
    assert(schedule.ShouldReport(31000));
    schedule.Reset();
    assert(!schedule.ShouldReport(1500));
    assert(schedule.ShouldReport(2100));
}

static void test_late_first_sample() {
    XrStallSchedule schedule;
    assert(schedule.ShouldReport(12000));
    assert(!schedule.ShouldReport(19000));
    assert(schedule.ShouldReport(20000));
}

static void test_watchdog() {
    std::mutex mutex;
    std::vector<std::string> reports;
    XrStallWatchdog watchdog;
    watchdog.Start([&](const char *stage, int64_t elapsedMs) {
        std::lock_guard<std::mutex> lock(mutex);
        assert(elapsedMs >= XrStallSchedule::kFirstMs);
        reports.push_back(stage);
    });
    for (int i = 0; i < 25; i++) {
        watchdog.Enter("busy");
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    {
        std::lock_guard<std::mutex> lock(mutex);
        assert(reports.empty());
    }
    watchdog.Enter("xrWaitFrame");
    std::this_thread::sleep_for(std::chrono::milliseconds(3200));
    watchdog.Stop();
    std::lock_guard<std::mutex> lock(mutex);
    assert(reports.size() == 1);
    assert(reports[0] == "xrWaitFrame");
}

int main() {
    test_schedule();
    test_late_first_sample();
    test_watchdog();
    std::puts("ok");
    return 0;
}
"""


def function_body(source, signature):
    start = source.index(signature)
    depth = 0
    for index in range(source.index("{", start), len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"unterminated {signature}")


class AndroidXrLaunchTest(unittest.TestCase):
    def test_stall_watchdog(self):
        compiler = shutil.which("c++") or shutil.which("clang++") or shutil.which("g++")
        self.assertIsNotNone(compiler, "C++ host compiler is required")
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "test_xr_stall.cpp"
            exe = Path(tmp) / "test_xr_stall"
            src.write_text(STALL_CPP, encoding="utf-8")
            compiled = subprocess.run(
                [compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-pthread",
                 f"-I{QUESTXR_DIR}", str(src), "-o", str(exe)],
                capture_output=True, text=True)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            ran = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(0, ran.returncode, ran.stderr)
            self.assertEqual("ok", ran.stdout.strip())

    def test_native_thread_outlives_the_xr_session(self):
        source = QUEST_XR.read_text(encoding="utf-8")
        main = function_body(source, "void android_main(android_app *app)")
        self.assertRegex(main, r"finishAndDrain\(app\);\s*}\s*$")
        self.assertNotIn("ANativeActivity_finish", main)
        self.assertNotIn("return", main)
        drain = function_body(source, "static void finishAndDrain(android_app *app)")
        self.assertIn("ANativeActivity_finish(app->activity)", drain)
        self.assertRegex(drain, r"while \(!app->destroyRequested\)\s*{[^}]*ALooper_pollOnce")
        run = function_body(source, "    void run()")
        self.assertNotIn("ANativeActivity_finish", run)

    def test_init_keeps_the_ui_thread_moving(self):
        source = QUEST_XR.read_text(encoding="utf-8")
        init = function_body(source, "    void initialize()")
        stages = re.findall(r'stage\("([^"]+)"\);', init)
        for expected in ("xrInitializeLoaderKHR", "xrCreateInstance", "xrCreateSession", "swapchains"):
            self.assertIn(expected, stages)
        self.assertLess(init.index('stage("xrCreateInstance")'), init.index("xrCreateInstance(&create"))
        self.assertLess(init.index('stage("xrCreateSession")'), init.index("xrCreateSession(instance_"))
        stage = function_body(source, "    void stage(const char *name)")
        self.assertIn("ALooper_pollOnce(0", stage)
        self.assertIn("source->process(app_, source)", stage)
        self.assertIn("destroyRequested", stage)

    def test_manifest_declares_the_android_xr_runtime_library(self):
        application = ET.parse(MANIFEST).getroot().find("application")
        libraries = {
            item.get(f"{ANDROID}name"): item.get(f"{ANDROID}required")
            for item in application.findall("uses-native-library")
        }
        self.assertEqual("false", libraries.get("libopenxr.google.so"))


if __name__ == "__main__":
    unittest.main()
