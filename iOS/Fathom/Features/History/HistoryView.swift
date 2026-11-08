import SwiftUI
import FathomShared

/// Every play Fathom read, grouped by the day the listener had, with a local
/// search and a jump to any month that appears in the history.
///
/// The grouping uses the device's offset, not the record's own, so a play at
/// half past midnight appears under today rather than under yesterday because
/// its timestamp was UTC. Search never reads the network: it filters the plays
/// already in memory.
struct HistoryView: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    @State private var index: PlaySearchIndex?

    private var nowEpochSeconds: Int64 { Int64(Date().timeIntervalSince1970) }
    private var tzOffsetMinutes: Int32 { Int32(TimeZone.current.secondsFromGMT() / 60) }

    private var plays: [Play] { model.snapshot?.plays ?? [] }

    /// The plays matching the current query, in the order the dashboard read
    /// them. A blank query is every play, not an empty result.
    private var matches: [Play] {
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let index else { return plays }
        return index.search(query: query)
    }

    /// Named `DaySection`, not `Section`: a nested type called `Section` would
    /// shadow SwiftUI's own `Section` inside this view.
    private struct DaySection: Identifiable {
        let id: Int64
        let plays: [Play]
    }

    private struct MonthOption: Identifiable {
        let id: String
        let label: String
        let firstDay: Int64
    }

    private var sections: [DaySection] {
        var buckets: [Int64: [Play]] = [:]
        for play in matches {
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

    /// Plays whose timestamp cannot be read are still searchable and still
    /// shown, under a heading that does not invent a day for them.
    private var undated: [Play] {
        matches.filter { FathomCore.shared.instant(raw: $0.playedTime) == Int64.min }
    }

    /// One entry per month present in the current results, newest first.
    private var months: [MonthOption] {
        var seen = Set<String>()
        return sections.compactMap { section in
            let key = FathomCore.shared.monthKey(epochDay: section.id)
            guard seen.insert(key).inserted else { return nil }
            return MonthOption(
                id: key,
                label: FathomCore.shared.monthLabel(epochDay: section.id),
                firstDay: section.id
            )
        }
    }

    /// Why a read stopped before the end, beyond the generic partial label.
    private var partialNotice: String {
        guard let failure = model.snapshot?.readFailure else {
            return "Only part of the history was read."
        }
        return "Only part of the history was read. \(failure)"
    }

    private var countText: String {
        if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "\(plays.count) plays"
        }
        return matches.count == 1 ? "1 match" : "\(matches.count) matches"
    }

    var body: some View {
        Group {
            if model.snapshot == nil {
                if model.loading {
                    ProgressView().padding(60)
                } else {
                    EmptyBlock(title: "Nothing loaded", body: "Tap Refresh to try again.")
                }
            } else if plays.isEmpty {
                if model.loading {
                    ProgressView("Reading history").padding(60)
                } else if model.snapshot?.sampled == true {
                    EmptyBlock(
                        title: "No plays read",
                        body: model.snapshot?.readFailure.map {
                            "The history stopped before any plays could be read. \($0)"
                        } ?? "Only part of the history was read, and no plays arrived."
                    )
                } else {
                    EmptyBlock(
                        title: "No history",
                        body: "This account has no plays in either teal.fm namespace."
                    )
                }
            } else {
                historyList
            }
        }
        .background(FathomPalette.background.ignoresSafeArea())
        .navigationTitle("History")
        // Rebuild the folded index when the loaded set changes, not on every
        // keystroke. The count is a cheap proxy for a new read; a reset count
        // after an account change is caught by the identity in the key.
        .task(id: "\(model.snapshot?.did ?? "")-\(plays.count)-\(model.snapshot?.sampled == true)") {
            index = FathomCore.shared.searchIndex(plays: plays)
        }
    }

    private var historyList: some View {
        ScrollViewReader { proxy in
            List {
                Section {
                    Text(countText)
                        .font(.caption)
                        .foregroundStyle(FathomPalette.muted)
                        .listRowBackground(FathomPalette.background)
                }

                if sections.isEmpty && undated.isEmpty {
                    EmptyBlock(
                        title: "No plays match",
                        body: "Try a track, an artist or a release name."
                    )
                    .listRowBackground(FathomPalette.background)
                } else {
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
                            .id(section.id)
                        }
                    }

                    if !undated.isEmpty {
                        Section {
                            ForEach(Array(undated.enumerated()), id: \.offset) { _, play in
                                PlayRowView(
                                    play: play,
                                    nowEpochSeconds: nowEpochSeconds,
                                    tzOffsetMinutes: tzOffsetMinutes
                                )
                                .listRowBackground(FathomPalette.surface)
                            }
                        } header: {
                            Text("No date")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(FathomPalette.teal)
                                .id("undated")
                        } footer: {
                            Text(
                                undated.count == 1
                                    ? "1 play has no readable timestamp."
                                    : "\(undated.count) plays have no readable timestamp."
                            )
                            .font(.caption2)
                            .foregroundStyle(FathomPalette.muted)
                        }
                    }
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
            .searchable(
                text: $query,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: "Track, artist or release"
            )
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if !months.isEmpty {
                        Menu {
                            ForEach(months) { month in
                                Button(month.label) {
                                    withAnimation {
                                        proxy.scrollTo(month.firstDay, anchor: .top)
                                    }
                                }
                            }
                        } label: {
                            Label("Jump to month", systemImage: "calendar")
                        }
                    }
                    Button("Refresh") { Task { await model.refresh() } }
                        .disabled(model.loading)
                }
            }
            .onChange(of: query) { _, _ in
                // A new query starts at the newest result instead of leaving
                // the list at a position that belonged to the old one.
                if let first = sections.first {
                    proxy.scrollTo(first.id, anchor: .top)
                }
            }
        }
    }
}
