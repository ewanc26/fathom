import SwiftUI
import FathomShared

/// The period's figures, with where they came from stated underneath.
///
/// The provenance line is not decoration: a teal.fm AppView would report
/// network-wide top artists, while Fathom reports what is in this repo. Both
/// are legitimately called "top artists" and they are not the same number.
struct StatsView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            if let snapshot = model.snapshot {
                VStack(spacing: 12) {
                    periodPicker
                    totalsPanel(snapshot)
                    extremesPanel(snapshot)
                    artistsPanel(snapshot)
                    releasesPanel(snapshot)
                    provenancePanel(snapshot)
                }
                .padding(16)
            } else if model.loading {
                ProgressView().padding(60)
            } else {
                EmptyBlock(title: "Nothing loaded", body: "Tap Refresh to try again.")
                    .padding(16)
            }
        }
        .background(FathomPalette.background.ignoresSafeArea())
        .refreshable { await model.refresh() }
        .navigationTitle("Stats")
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

    private var periodPicker: some View {
        Picker("Period", selection: Binding(
            get: { model.periodId },
            set: { id in Task { await model.selectPeriod(id) } }
        )) {
            ForEach(FathomCore.shared.periodIds(), id: \.self) { id in
                Text(FathomCore.shared.periodLabel(id: id)).tag(id)
            }
        }
        .pickerStyle(.segmented)
    }

    private func totalsPanel(_ snapshot: DashboardSnapshot) -> some View {
        let stats = snapshot.stats
        return Panel {
            VStack(spacing: 16) {
                HStack(spacing: 12) {
                    StatTile(
                        value: FathomCore.shared.countText(value: stats.playCount),
                        label: "plays"
                    )
                    StatTile(
                        value: FathomCore.shared.listeningText(seconds: stats.listenedSeconds),
                        label: "in tracks"
                    )
                }
                HStack(spacing: 12) {
                    StatTile(
                        value: FathomCore.shared.countText(value: stats.uniqueArtists),
                        label: "artists"
                    )
                    StatTile(
                        value: FathomCore.shared.countText(value: stats.uniqueReleases),
                        label: "releases"
                    )
                    StatTile(
                        value: FathomCore.shared.countText(value: stats.uniqueTracks),
                        label: "tracks"
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func extremesPanel(_ snapshot: DashboardSnapshot) -> some View {
        let stats = snapshot.stats
        if let busiest = stats.busiestDay {
            Panel {
                HStack(alignment: .top, spacing: 12) {
                    StatTile(
                        value: "\(busiest.playCount)",
                        label: "on the busiest day",
                        detail: busiest.dayKey
                    )
                    StatTile(
                        value: FathomCore.shared.serviceLabel(host: stats.topService) ?? "—",
                        label: "most used service"
                    )
                }
            }
        }
    }

    private func artistsPanel(_ snapshot: DashboardSnapshot) -> some View {
        let artists = snapshot.stats.topArtists
        return Panel {
            VStack(alignment: .leading, spacing: 4) {
                SectionTitle(text: "Top artists")
                if artists.isEmpty {
                    EmptyBlock(title: "No artists", body: "Nothing was played in this period.")
                } else {
                    let leader = max(artists[0].playCount, 1)
                    ForEach(Array(artists.enumerated()), id: \.offset) { index, artist in
                        RankRow(
                            rank: index + 1,
                            title: artist.name,
                            playCount: artist.playCount,
                            fraction: Double(artist.playCount) / Double(leader)
                        )
                    }
                }
            }
        }
    }

    private func releasesPanel(_ snapshot: DashboardSnapshot) -> some View {
        let releases = snapshot.stats.topReleases
        return Panel {
            VStack(alignment: .leading, spacing: 4) {
                SectionTitle(text: "Top releases")
                if releases.isEmpty {
                    EmptyBlock(
                        title: "No releases",
                        body: "The plays in this period name no release."
                    )
                } else {
                    let leader = max(releases[0].playCount, 1)
                    ForEach(Array(releases.enumerated()), id: \.offset) { index, release in
                        RankRow(
                            rank: index + 1,
                            title: release.name,
                            playCount: release.playCount,
                            fraction: Double(release.playCount) / Double(leader)
                        )
                    }
                }
            }
        }
    }

    private func provenancePanel(_ snapshot: DashboardSnapshot) -> some View {
        var notes = [FathomCore.shared.provenanceComputedLocally()]
        if snapshot.sampled {
            notes.append(
                model.loading
                    ? "Still reading the rest of the history; these figures will grow"
                    : "Only part of the history was read, so this is not a complete total"
            )
        }
        if snapshot.skipped > 0 {
            notes.append("\(snapshot.skipped) unreadable records were skipped")
        }
        if snapshot.usedLegacyNamespace {
            notes.append("Includes records from the older \(FathomCore.shared.legacyNamespace()) namespace")
        }

        return NoticeBox(text: notes.joined(separator: ". ") + ".")
    }
}
