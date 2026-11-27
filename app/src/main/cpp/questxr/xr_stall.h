#pragma once
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <utility>

struct XrStallSchedule {
    static constexpr int64_t kFirstMs = 2000;
    static constexpr int64_t kSecondMs = 5000;
    static constexpr int64_t kRepeatMs = 10000;

    int64_t reportedMs = 0;

    void Reset() { reportedMs = 0; }

    bool ShouldReport(int64_t elapsedMs) {
        int64_t due = reportedMs < kFirstMs ? kFirstMs
            : reportedMs < kSecondMs ? kSecondMs
            : (reportedMs / kRepeatMs + 1) * kRepeatMs;
        if (elapsedMs < due) return false;
        reportedMs = elapsedMs;
        return true;
    }
};

class XrStallWatchdog {
public:
    using Report = std::function<void(const char *stage, int64_t elapsedMs)>;

    ~XrStallWatchdog() { Stop(); }

    void Start(Report report) {
        Stop();
        report_ = std::move(report);
        stop_ = false;
        Enter("start");
        thread_ = std::thread([this] { Run(); });
    }

    void Stop() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stop_ = true;
        }
        wake_.notify_all();
        if (thread_.joinable()) thread_.join();
    }

    void Enter(const char *stage) {
        sinceMs_.store(NowMs(), std::memory_order_release);
        stage_.store(stage, std::memory_order_relaxed);
        epoch_.fetch_add(1, std::memory_order_relaxed);
    }

    const char *Stage() const { return stage_.load(std::memory_order_relaxed); }

private:
    static int64_t NowMs() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    void Run() {
        XrStallSchedule schedule;
        uint64_t seen = 0;
        std::unique_lock<std::mutex> lock(mutex_);
        while (!stop_) {
            wake_.wait_for(lock, std::chrono::milliseconds(500));
            if (stop_) break;
            const uint64_t epoch = epoch_.load(std::memory_order_relaxed);
            if (epoch != seen) { seen = epoch; schedule.Reset(); }
            const int64_t elapsed = NowMs() - sinceMs_.load(std::memory_order_acquire);
            if (schedule.ShouldReport(elapsed) && report_) report_(Stage(), elapsed);
        }
    }

    std::atomic<const char *> stage_{"start"};
    std::atomic<uint64_t> epoch_{0};
    std::atomic<int64_t> sinceMs_{0};
    std::mutex mutex_;
    std::condition_variable wake_;
    std::thread thread_;
    bool stop_ = true;
    Report report_;
};
