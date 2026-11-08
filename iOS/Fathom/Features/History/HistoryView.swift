import SwiftUI
import FathomShared

/// Every play Fathom read, grouped by the day the listener had.
///
/// The grouping uses the device's offset, not the record's own, so a play at
/// half past midnight appears under today rather than under yesterday because
/// its timestamp was UTC.
struct HistoryView: View {
    @EnvironmentObject private var model: AppModel

    private var nowEpochSeconds: Int64 { Int64(Date().timeIntervalSince1970) }
    private var tzOffsetMinutes: Int32 { Int32(TimeZone.current.secondsFromGMT() / 60) }

    /// Named `DaySection`, not `Section`: a nested type called `Section` would
    /// shadow SwiftUI's own `Section` inside this view.
    private struct DaySection: Identifiable {
        let id: Int64
        let plays: [Play]
    }

    private var sections: [DaySection] {
        guard let plays = model.snapshot?.plays else { return [] }
        var buckets: [Int64: [Play]] = [:]
        for play in plays {
            let instant = FathomCore.shared.instant(raw: play.playedTime)
            guard instant != Int64.min else { continue }
            let day = FathomCore.shared.epochDay(
                epochSeconds: instant,
                tzOffsetMinutes: tzOffsetMinutes
            )
            buckets[day, default: []].append(play)
        }
        return buckets.keys.sorted(by: >).map { DaySection(id: $0, plays: buckets[$0] ?? []) }
    }

    /// Why a read stopped before the end, beyond the generic partial label.
    private var partialNotice: String {
        guard let failure = model.snapshot?.readFailure else {
            return "Only part of the history was read."
        }
        return "Only part of the history was read. \(failure)"
    }

    /// What History says about records it cannot place under a day.
    private var undatedNotice: String? {
        let count = Int(model.snapshot?.stats.undatedPlays ?? 0)
        guard count > 0 else { return nil }
        return count == 1
            ? "1 play has no readable timestamp and is not shown."
            : "\(count) plays have no readable timestamp and are not shown."
    }

    var body: some View {
        Group {
            if model.snapshot == nil {
                if model.loading {
                    ProgressView().padding(60)
                } else {
                    EmptyBlock(title: "Nothing loaded", body: "Tap Refresh to try again.")
                }
            } else if sections.isEmpty {
                if model.loading {
                    ProgressView("Reading history").padding(60)
                } else if model.snapshot?.sampled == true {
                    EmptyBlock(
                        title: "No plays read",
                        body: model.snapshot?.readFailure.map {
                            "The history stopped before any plays could be read. \($0)"
                        } ?? "Only part of the history was read, and no dated plays arrived."
                    )
                } else if let notice = undatedNotice {
                    EmptyBlock(title: "No dated plays", body: notice)
                } else {
                    EmptyBlock(
                        title: "No history",
                        body: "This account has no dated plays in either teal.fm namespace."
                    )
                }
            } else {
                historyList
            }
        }
        .background(FathomPalette.background.ignoresSafeArea())
        .navigationTitle("History")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Refresh") { Task { await model.refresh() } }
                    .disabled(model.loading)
            }
        }
    }

    private var historyList: some View {
        List {
            ForEach(sections) { section in
                Section {
                    ForEach(Array(section.plays.enumerated()), id: \.offset) { _, play in
                        PlayRowView(
                            play: play,
                            nowEpochSeconds: nowEpochSeconds,
                            tzOffsetMinutes: tzOffsetMinutes
                        )
                        .listRowBackground(FathomPalette.surface)
                    }
                } header: {
                    Text(
                        FathomCore.shared.friendlyDay(
                            epochDay: section.id,
                            todayEpochDay: FathomCore.shared.epochDay(
                                epochSeconds: nowEpochSeconds,
                                tzOffsetMinutes: tzOffsetMinutes
                            )
                        )
                    )
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(FathomPalette.teal)
                }
            }

            if let notice = undatedNotice {
                Text(notice)
                    .font(.caption2)
                    .foregroundStyle(FathomPalette.muted)
                    .listRowBackground(FathomPalette.background)
            }

            if model.snapshot?.sampled == true {
                Text(
                    model.loading
                        ? "Still reading the rest of the history."
                        : partialNotice
                )
                    .font(.caption2)
                    .foregroundStyle(FathomPalette.muted)
                    .listRowBackground(FathomPalette.background)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .refreshable { await model.refresh() }
    }
}
