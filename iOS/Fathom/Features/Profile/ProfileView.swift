import SwiftUI
import FathomShared

/// Who the dashboard is about, and what it is reading.
///
/// The namespace line matters: an account mid-migration holds plays in both
/// `fm.teal.*` and `fm.teal.alpha.*`, and someone comparing Fathom's total
/// with teal.fm's needs to know both are counted.
struct ProfileView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScrollView {
            if let snapshot = model.snapshot {
                VStack(spacing: 12) {
                    identityPanel(snapshot)
                    accountPanel(snapshot)
                    methodPanel
                    Button("Disconnect", role: .destructive) {
                        model.disconnect()
                    }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)

                    Text("Fathom keeps one copy of your public history on this device so the next launch opens instantly. Disconnecting deletes it along with the handle.")
                        .font(.caption2)
                        .foregroundStyle(FathomPalette.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(16)
            } else {
                EmptyBlock(title: "Nothing loaded", body: "Tap Refresh to try again.")
                    .padding(16)
            }
        }
        .background(FathomPalette.background.ignoresSafeArea())
        .navigationTitle("Profile")
    }

    private func displayName(_ snapshot: DashboardSnapshot) -> String {
        if let name = snapshot.profile?.nameOrNull(), !name.isEmpty { return name }
        return snapshot.handle ?? snapshot.did
    }

    private func identityPanel(_ snapshot: DashboardSnapshot) -> some View {
        Panel {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 14) {
                    Monogram(name: displayName(snapshot))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(displayName(snapshot))
                            .font(.title3.weight(.semibold))
                        Text(snapshot.handle ?? snapshot.did)
                            .font(.caption)
                            .foregroundStyle(FathomPalette.muted)
                    }
                }

                if let description = snapshot.profile?.description, !description.isEmpty {
                    Text(description)
                        .font(.subheadline)
                        .foregroundStyle(FathomPalette.muted)
                }

                if let featured = snapshot.profile?.featuredItem {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Featured \(featured.type ?? "item")")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(FathomPalette.teal)
                        if let mbid = featured.mbid {
                            Text(mbid)
                                .font(.caption2)
                                .foregroundStyle(FathomPalette.muted)
                        }
                    }
                }
            }
        }
    }

    private func accountPanel(_ snapshot: DashboardSnapshot) -> some View {
        Panel {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(text: "Account")
                detail("DID", snapshot.did)
                detail("Handle", snapshot.handle ?? "Resolved from the DID")
                detail("Plays read", "\(snapshot.plays.count)")
                detail(
                    "Profile record",
                    snapshot.profile == nil ? "None — showing the handle instead" : "Present"
                )
            }
        }
    }

    private var methodPanel: some View {
        Panel {
            VStack(alignment: .leading, spacing: 10) {
                SectionTitle(text: "How these figures are made")
                NoticeBox(
                    text: "\(FathomCore.shared.provenanceComputedLocally()). teal.fm's own "
                        + "stats endpoints aggregate across the network, so a figure here and "
                        + "a figure on teal.fm are not the same measurement."
                )
                Text("Reads \(FathomCore.shared.productionNamespace()) and the older "
                    + "\(FathomCore.shared.legacyNamespace()), unauthenticated, from this "
                    + "account's PDS.")
                    .font(.caption2)
                    .foregroundStyle(FathomPalette.muted)
            }
        }
    }

    private func detail(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(FathomPalette.muted)
            Text(value)
                .font(.subheadline)
        }
    }
}
