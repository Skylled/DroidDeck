from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
QUESTXR_DIR = REPO / "app/src/main/cpp/questxr"
VR_SRC_DIR = REPO / "tools/vr/src"

TEST_CPP = r"""
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include "xr_android_xr.h"

static bool stereo_bounds_ok(uint32_t eye_w, uint32_t eye_h) {
    uint32_t stereo_w = eye_w * 2u;
    return stereo_w >= 2u && !(stereo_w & 1u) && eye_h > 0u &&
           stereo_w <= 8192u && eye_h <= 4096u &&
           uint64_t(stereo_w) * eye_h * 4ULL <= DROIDDECK_XR_FRAME_MAX_BYTES;
}

static bool approx(float a, float b, float eps = 1e-3f) {
    return std::fabs(a - b) <= eps;
}

static void test_galaxy_xr_clamping() {
    uint32_t w = 0, h = 0;

    // Galaxy XR native 3552x3840 with Balanced preset (1800 max px)
    xr_compute_target_eye_size(3552, 3840, 1800, 0, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w == 1664 && h == 1800);
    assert((w % 8) == 0 && (h % 8) == 0);
    assert(2ULL * w * h * 4ULL <= DROIDDECK_XR_FRAME_MAX_BYTES);
    assert(stereo_bounds_ok(w, h));
    float orig_aspect = 3552.0f / 3840.0f;
    float new_aspect = static_cast<float>(w) / static_cast<float>(h);
    assert(std::fabs(orig_aspect - new_aspect) < 0.01f);

    // Galaxy XR with Ultra preset (2880 max px)
    xr_compute_target_eye_size(3552, 3840, 2880, 0, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w == 2664 && h == 2880);
    assert(2ULL * w * h * 4ULL <= DROIDDECK_XR_FRAME_MAX_BYTES);
    assert(stereo_bounds_ok(w, h));

    // Galaxy XR unclamped (max_eye_px=0, scale=0) must still clamp under 64 MiB
    xr_compute_target_eye_size(3552, 3840, 0, 0, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w <= 4096 && h <= 4096);
    assert((w % 8) == 0 && (h % 8) == 0);
    assert(2ULL * w * h * 4ULL <= DROIDDECK_XR_FRAME_MAX_BYTES);
    assert(stereo_bounds_ok(w, h));
    assert(w >= 2700 && h >= 2900);

    // Explicit scale_percent (50..100)
    xr_compute_target_eye_size(3552, 3840, 0, 50, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w == 1776 && h == 1920);
    assert(2ULL * w * h * 4ULL <= DROIDDECK_XR_FRAME_MAX_BYTES);

    // Oversized 8K per eye clamped cleanly under both 4096 and byte budget
    xr_compute_target_eye_size(8192, 8192, 0, 0, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w <= 4096 && h <= 4096);
    assert(2ULL * w * h * 4ULL <= DROIDDECK_XR_FRAME_MAX_BYTES);
    assert(stereo_bounds_ok(w, h));
}

static void test_quest_passthrough() {
    uint32_t w = 0, h = 0;

    // Quest 3 typical recommended sizes remain exact when max_eye_px=0
    xr_compute_target_eye_size(1680, 1760, 0, 0, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w == 1680 && h == 1760);

    xr_compute_target_eye_size(2064, 2208, 0, 0, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w == 2064 && h == 2208);

    // Zero inputs return 0x0 safely
    xr_compute_target_eye_size(0, 1080, 1800, 0, DROIDDECK_XR_FRAME_MAX_BYTES, &w, &h);
    assert(w == 0 && h == 0);
}

static void test_refresh_rate_selection() {
    const float galaxy_rates[] = {60.0f, 72.0f, 90.0f};
    assert(approx(xr_pick_refresh_rate(galaxy_rates, 3, 0.0f), 72.0f));
    assert(approx(xr_pick_refresh_rate(galaxy_rates, 3, 90.0f), 90.0f));
    assert(approx(xr_pick_refresh_rate(galaxy_rates, 3, 60.0f), 60.0f));
    assert(approx(xr_pick_refresh_rate(galaxy_rates, 3, 80.0f), 72.0f));
    assert(approx(xr_pick_refresh_rate(galaxy_rates, 3, 85.0f), 90.0f));

    // Tie favors higher refresh rate
    const float tie_rates[] = {60.0f, 90.0f};
    assert(approx(xr_pick_refresh_rate(tie_rates, 2, 75.0f), 90.0f));

    // Empty / invalid lists return 0
    assert(approx(xr_pick_refresh_rate(nullptr, 0, 72.0f), 0.0f));
    const float bad_rates[] = {0.0f, -1.0f};
    assert(approx(xr_pick_refresh_rate(bad_rates, 2, 72.0f), 0.0f));
}

static XrHandJointSample make_joint(float x, float y, float z, uint32_t flags = 15u) {
    return {{0.0f, 0.0f, 0.0f, 1.0f}, {x, y, z}, flags};
}

static void test_hand_tracking_synthesis() {
    // Open hand: no pinch, no grip
    XrHandSynthesisInput open_hand{};
    open_hand.palm = make_joint(0.2f, 1.2f, -0.4f);
    open_hand.wrist = make_joint(0.2f, 1.15f, -0.35f);
    open_hand.thumb_tip = make_joint(0.15f, 1.25f, -0.45f);
    open_hand.index_tip = make_joint(0.23f, 1.25f, -0.45f); // 8 cm apart
    open_hand.middle_tip = make_joint(0.2f, 1.30f, -0.4f);  // 10 cm from palm
    open_hand.ring_tip = make_joint(0.22f, 1.30f, -0.4f);
    open_hand.pinky_tip = make_joint(0.24f, 1.29f, -0.4f);

    DroidDeckXrHand out{};
    assert(xr_synthesize_hand_controller(open_hand, &out));
    assert(out.active == 1u);
    assert(approx(out.pose.position[0], 0.2f) && approx(out.pose.position[1], 1.2f) && approx(out.pose.position[2], -0.4f));
    assert(approx(out.trigger, 0.0f));
    assert(approx(out.squeeze, 0.0f));
    assert((out.buttons & DROIDDECK_XR_TRIGGER_TOUCH) == 0u);

    // Full pinch (1.0 cm) + closed fist grip (4.0 cm)
    XrHandSynthesisInput pinched = open_hand;
    pinched.thumb_tip = make_joint(0.20f, 1.25f, -0.45f);
    pinched.index_tip = make_joint(0.21f, 1.25f, -0.45f); // 1.0 cm
    pinched.middle_tip = make_joint(0.20f, 1.24f, -0.40f); // 4.0 cm
    pinched.ring_tip = make_joint(0.20f, 1.24f, -0.40f);
    pinched.pinky_tip = make_joint(0.20f, 1.24f, -0.40f);
    assert(xr_synthesize_hand_controller(pinched, &out));
    assert(out.active == 1u);
    assert(approx(out.trigger, 1.0f));
    assert(approx(out.squeeze, 1.0f));
    assert((out.buttons & DROIDDECK_XR_TRIGGER_TOUCH) != 0u);

    // Half pinch (3.0 cm -> 0.5)
    XrHandSynthesisInput half = open_hand;
    half.thumb_tip = make_joint(0.20f, 1.25f, -0.45f);
    half.index_tip = make_joint(0.23f, 1.25f, -0.45f); // 3.0 cm
    assert(xr_synthesize_hand_controller(half, &out));
    assert(approx(out.trigger, 0.5f));
    assert((out.buttons & DROIDDECK_XR_TRIGGER_TOUCH) != 0u);

    // Untracked palm and wrist returns false and inactive
    XrHandSynthesisInput untracked = open_hand;
    untracked.palm.flags = 0u;
    untracked.wrist.flags = 0u;
    assert(!xr_synthesize_hand_controller(untracked, &out));
    assert(out.active == 0u);

    // Wrist fallback when palm is untracked
    XrHandSynthesisInput wrist_only = open_hand;
    wrist_only.palm.flags = 0u;
    assert(xr_synthesize_hand_controller(wrist_only, &out));
    assert(out.active == 1u);
    assert(approx(out.pose.position[1], 1.15f));
}

int main() {
    test_galaxy_xr_clamping();
    test_quest_passthrough();
    test_refresh_rate_selection();
    test_hand_tracking_synthesis();
    std::puts("ok");
    return 0;
}
"""


class AndroidXrNativeHelperTest(unittest.TestCase):
    def test_xr_android_xr_header(self):
        compiler = shutil.which("c++") or shutil.which("clang++") or shutil.which("g++")
        self.assertIsNotNone(compiler, "C++ host compiler is required")
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "test_xr_android_xr.cpp"
            exe = Path(tmp) / "test_xr_android_xr"
            src.write_text(TEST_CPP, encoding="utf-8")
            compile_res = subprocess.run(
                [
                    compiler,
                    "-std=c++17",
                    "-Wall",
                    "-Wextra",
                    "-Werror",
                    f"-I{QUESTXR_DIR}",
                    f"-I{VR_SRC_DIR}",
                    str(src),
                    "-o",
                    str(exe),
                ],
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, compile_res.returncode, compile_res.stderr)
            run_res = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(0, run_res.returncode, run_res.stderr)
            self.assertEqual("ok", run_res.stdout.strip())


if __name__ == "__main__":
    unittest.main()
