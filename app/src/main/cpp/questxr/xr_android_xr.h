#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include "xr_frame.h"
#include "xr_tracking.h"

struct XrHandJointSample {
    float orientation[4];
    float position[3];
    uint32_t flags;
};

struct XrHandSynthesisInput {
    XrHandJointSample palm;
    XrHandJointSample wrist;
    XrHandJointSample thumb_tip;
    XrHandJointSample index_tip;
    XrHandJointSample middle_tip;
    XrHandJointSample ring_tip;
    XrHandJointSample pinky_tip;
};

inline void xr_compute_target_eye_size(
    uint32_t rec_w,
    uint32_t rec_h,
    uint32_t max_eye_px,
    uint32_t scale_percent,
    uint32_t max_frame_bytes,
    uint32_t *out_w,
    uint32_t *out_h) {
    if (!out_w || !out_h) return;
    if (rec_w == 0 || rec_h == 0) {
        *out_w = 0;
        *out_h = 0;
        return;
    }

    const uint64_t budget = max_frame_bytes > 0 ? max_frame_bytes : DROIDDECK_XR_FRAME_MAX_BYTES;
    double w = static_cast<double>(rec_w);
    double h = static_cast<double>(rec_h);

    if (scale_percent >= 50u && scale_percent <= 100u) {
        const double scale = static_cast<double>(scale_percent) / 100.0;
        w *= scale;
        h *= scale;
    }

    if (max_eye_px > 0u) {
        const double max_dim = std::max(w, h);
        if (max_dim > static_cast<double>(max_eye_px)) {
            const double scale = static_cast<double>(max_eye_px) / max_dim;
            w *= scale;
            h *= scale;
        }
    }

    if (w > 4096.0 || h > 4096.0) {
        const double scale = 4096.0 / std::max(w, h);
        w *= scale;
        h *= scale;
    }

    const double stereo_bytes = 8.0 * w * h;
    if (stereo_bytes > static_cast<double>(budget)) {
        const double scale = std::sqrt(static_cast<double>(budget) / stereo_bytes);
        w *= scale;
        h *= scale;
    }

    auto align_down = [](double value) -> uint32_t {
        if (value < 8.0) return 8u;
        uint32_t floored = static_cast<uint32_t>(std::floor(value));
        uint32_t aligned = (floored / 8u) * 8u;
        return aligned >= 8u ? aligned : 8u;
    };

    uint32_t final_w = align_down(w);
    uint32_t final_h = align_down(h);

    while ((final_w > 4096u || final_h > 4096u || 8ULL * final_w * final_h > budget) &&
           (final_w > 8u || final_h > 8u)) {
        if (final_w >= final_h && final_w > 8u) {
            final_w -= 8u;
        } else if (final_h > 8u) {
            final_h -= 8u;
        } else {
            final_w -= 8u;
        }
    }

    *out_w = final_w;
    *out_h = final_h;
}

inline float xr_pick_refresh_rate(const float *supported, size_t count, float target_hz) {
    if (!supported || count == 0u) return 0.0f;
    const float desired = (std::isfinite(target_hz) && target_hz > 1.0f) ? target_hz : 72.0f;
    float best = 0.0f;
    float best_diff = 0.0f;
    bool found = false;
    for (size_t i = 0; i < count; i++) {
        const float rate = supported[i];
        if (!std::isfinite(rate) || rate <= 1.0f) continue;
        const float diff = std::fabs(rate - desired);
        if (!found || diff < best_diff - 1e-4f || (std::fabs(diff - best_diff) <= 1e-4f && rate > best)) {
            best = rate;
            best_diff = diff;
            found = true;
        }
    }
    return best;
}

inline bool xr_synthesize_hand_controller(const XrHandSynthesisInput &joints, DroidDeckXrHand *out_hand) {
    if (!out_hand) return false;
    *out_hand = {};

    auto is_finite_pose = [](const XrHandJointSample &s) -> bool {
        float norm_sq = 0.0f;
        for (float v : s.orientation) {
            if (!std::isfinite(v)) return false;
            norm_sq += v * v;
        }
        for (float v : s.position) {
            if (!std::isfinite(v)) return false;
        }
        return norm_sq > 1e-6f;
    };
    auto has_tracked_pose = [&](const XrHandJointSample &s) -> bool {
        return (s.flags & 15u) == 15u && is_finite_pose(s);
    };
    auto has_valid_pos = [](const XrHandJointSample &s) -> bool {
        if ((s.flags & 2u) == 0u) return false;
        for (float v : s.position) {
            if (!std::isfinite(v)) return false;
        }
        return true;
    };
    auto dist = [](const float a[3], const float b[3]) -> float {
        const float dx = a[0] - b[0];
        const float dy = a[1] - b[1];
        const float dz = a[2] - b[2];
        return std::sqrt(dx * dx + dy * dy + dz * dz);
    };

    const XrHandJointSample *anchor = nullptr;
    if (has_tracked_pose(joints.palm)) {
        anchor = &joints.palm;
    } else if (has_tracked_pose(joints.wrist)) {
        anchor = &joints.wrist;
    } else {
        return false;
    }

    float norm_sq = 0.0f;
    for (float v : anchor->orientation) norm_sq += v * v;
    const float inv_norm = 1.0f / std::sqrt(norm_sq);
    for (int i = 0; i < 4; i++) out_hand->pose.orientation[i] = anchor->orientation[i] * inv_norm;
    for (int i = 0; i < 3; i++) out_hand->pose.position[i] = anchor->position[i];
    out_hand->pose.flags = anchor->flags;

    if (has_valid_pos(joints.thumb_tip) && has_valid_pos(joints.index_tip)) {
        const float pinch_m = dist(joints.thumb_tip.position, joints.index_tip.position);
        constexpr float kPinchClosed = 0.015f;
        constexpr float kPinchOpen = 0.045f;
        out_hand->trigger = std::clamp((kPinchOpen - pinch_m) / (kPinchOpen - kPinchClosed), 0.0f, 1.0f);
        if (out_hand->trigger > 0.1f) {
            out_hand->buttons |= DROIDDECK_XR_TRIGGER_TOUCH;
        }
    }

    const XrHandJointSample *curl_tips[3] = {&joints.middle_tip, &joints.ring_tip, &joints.pinky_tip};
    float curl_sum = 0.0f;
    int curl_count = 0;
    for (const XrHandJointSample *tip : curl_tips) {
        if (!has_valid_pos(*tip)) continue;
        curl_sum += dist(anchor->position, tip->position);
        curl_count++;
    }
    if (curl_count > 0) {
        const float avg_dist = curl_sum / static_cast<float>(curl_count);
        constexpr float kGripClosed = 0.045f;
        constexpr float kGripOpen = 0.090f;
        out_hand->squeeze = std::clamp((kGripOpen - avg_dist) / (kGripOpen - kGripClosed), 0.0f, 1.0f);
    }

    out_hand->active = 1u;
    return true;
}
