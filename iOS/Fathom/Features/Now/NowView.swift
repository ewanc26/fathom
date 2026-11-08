import SwiftUI
import FathomShared

/// What is playing, what today has been, and how the last fortnight looks.
///
/// Today's figures are the last bucket of the fourteen-day series rather than
/// a second aggregation, so the screen cannot contradict itself.
struct NowView: View {
    @EnvironmentObject private var model: AppModel

    private var nowEpochSeconds: Int64 { Int64(Date().timeIntervalSince1970) }
    private var tzOffsetMinutes: Int32 { Int32(TimeZone.current.secondsFromGMT() / 60) }

    var body: some View {
        ScrollView {
            if let snapshot = model.snapshot {
                VStack(spacing: 12) {
                    NowPlayingCard(snapshot: snapshot, now: nowEpochSeconds, tz: tzOffsetMinutes)
                    todayPanel(snapshot)
                    chartPanel(snapshot)
                    recentPanel(snapshot)
                }
                .padding(16)
            } else if model.loading {
                ProgressView().padding(60)
            } else {
                EmptyBlock(title: "Nothing loaded", body: model.error ?? "Tap Refresh to try again.")
                    .padding(16)
            }
        }
        .background(FathomPalette.background.ignoresSafeArea())
        .refreshable { await model.refresh() }
        .navigationTitle("Now")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Refresh") { Task { await model.refresh() } }
                    .disabled(model.loading)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let error = model.error {
                NoticeBox(text: error).padding(12)
            }
        }
    }

    private func todayPanel(_ snapshot: DashboardSnapshot) -> some View {
        let today = snapshot.stats.recentDays.last
        return Panel {
            HStack(alignment: .top, spacing: 12) {
                StatTile(value: "\(today?.playCount ?? 0)", label: "plays today")
                StatTile(
                    value: FathomCore.shared.listeningText(seconds: today?.seconds ?? 0),
                    label: "time today"
                )
                StatTile(
                    value: "\(snapshot.stats.currentStreakDays)",
                    label: "day streak"
                )
            }
        }
    }

    private func chartPanel(_ snapshot: DashboardSnapshot) -> some View {
        Panel {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(text: "Last 14 days")
                Text("\(snapshot.stats.recentPlayCount) plays in the last 14 days")
                    .font(.caption2)
                    .foregroundStyle(FathomPalette.muted)
                DayChart(days: snapshot.stats.recentDays)
                HStack {
                    Text(startLabel(snapshot.stats.recentDays))
                        .font(.caption2)
                        .foregroundStyle(FathomPalette.muted)
                    Spacer()
                    Text("today")
                        .font(.caption2)
                        .foregroundStyle(FathomPalette.muted)
                }
            }
        }
    }

    private func recentPanel(_ snapshot: DashboardSnapshot) -> some View {
        Panel {
            VStack(alignment: .leading, spacing: 4) {
                SectionTitle(text: "Recently played")
                if snapshot.plays.isEmpty {
                    EmptyBlock(
                        title: "Nothing yet",
                        body: "This account has no teal.fm plays in either namespace."
                    )
                } else {
                    ForEach(Array(snapshot.plays.prefix(4).enumerated()), id: \.offset) { _, play in
                        PlayRowView(
                            play: play,
                            nowEpochSeconds: nowEpochSeconds,
                            tzOffsetMinutes: tzOffsetMinutes
                        )
                    }
                }
            }
        }
    }

    private func startLabel(_ days: [DayBucket]) -> String {
        guard let first = days.first, let last = days.last else { return "" }
        return FathomCore.shared.shortDate(
            epochDay: first.epochDay,
            todayEpochDay: last.epochDay
        )
    }
}

private struct NowPlayingCard: View {
    let snapshot: DashboardSnapshot
    let now: Int64
    let tz: Int32

    private var status: TealStatus? { snapshot.status }

    /// A status record still exists after it expires, and a tracker with
    /// nothing to report writes one with an empty track, so both are checked.
    private var isLive: Bool {
        guard let status, status.item?.hasTrack() == true else { return false }
        let expiry = FathomCore.shared.instant(raw: status.expiry)
        return expiry == Int64.min || now < expiry
    }

    var body: some View {
        Panel {
            VStack(alignment: .leading, spacing: 6) {
                Text(isLive ? "NOW PLAYING" : "NOT PLAYING")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(isLive ? FathomPalette.teal : FathomPalette.muted)

                if isLive, let item = status?.item {
                    Text(item.title())
                        .font(.title2.weight(.semibold))
                    Text(item.artistLine())
                        .font(.subheadline)
                        .foregroundStyle(FathomPalette.muted)

                    if let raw = status?.time {
                        let started = FathomCore.shared.instant(raw: raw)
                        if started != Int64.min {
                            Text("since \(FathomCore.shared.clockTime(epochSeconds: started, tzOffsetMinutes: tz))")
                                .font(.caption2)
                                .foregroundStyle(FathomPalette.muted)
                        }
                    }
                } else {
                    Text("Nothing is playing right now.")
                        .font(.subheadline)
                        .foregroundStyle(FathomPalette.muted)
                    Text("A status record appears here while a tracker is running.")
                        .font(.caption2)
                        .foregroundStyle(FathomPalette.muted)
                }
            }
        }
    }
}
