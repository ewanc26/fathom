import SwiftUI
import FathomShared

/// Version, source, policies and the licences a binary has to carry.
///
/// The AGPL text is bundled rather than fetched, so the licence remains
/// readable with no network and after the app has been installed from a store.
struct AboutView: View {
    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
    }

    private var build: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "unknown"
    }

    private var licenceText: String {
        guard let url = Bundle.main.url(forResource: "AGPL-3.0", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return "The licence text could not be read from this build."
        }
        return text
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                Panel {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(FathomCore.shared.appName())
                            .font(.title3.weight(.semibold))
                        Text("Version \(version) (\(build))")
                            .font(.subheadline)
                        Text(FathomCore.shared.appDescription())
                            .font(.caption)
                            .foregroundStyle(FathomPalette.muted)
                        Text(FathomCore.shared.copyright())
                            .font(.caption2)
                            .foregroundStyle(FathomPalette.muted)
                    }
                }

                Panel {
                    VStack(alignment: .leading, spacing: 6) {
                        SectionTitle(text: "Links")
                        Link("Source code", destination: url(FathomCore.shared.sourceUrl()))
                        Link("Privacy policy", destination: url(FathomCore.shared.privacyUrl()))
                        Link("Terms of use", destination: url(FathomCore.shared.termsUrl()))
                        Link("Support and feedback", destination: url(FathomCore.shared.supportUrl()))
                        Link("GNU AGPL-3.0", destination: url(FathomCore.shared.licenceUrl()))
                    }
                    .font(.subheadline)
                }

                Panel {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionTitle(text: "Third-party notices")
                        Text(FathomCore.shared.thirdPartyNotices())
                            .font(.caption)
                            .foregroundStyle(FathomPalette.muted)
                    }
                }

                Panel {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionTitle(text: FathomCore.shared.licenceName())
                        Text("The full licence is bundled with the app and reproduced below.")
                            .font(.caption2)
                            .foregroundStyle(FathomPalette.muted)
                        DisclosureGroup("Read the licence") {
                            Text(licenceText)
                                .font(.caption2)
                                .foregroundStyle(FathomPalette.muted)
                                .textSelection(.enabled)
                                .padding(.top, 8)
                        }
                        .font(.subheadline)
                    }
                }
            }
            .padding(16)
        }
        .background(FathomPalette.background.ignoresSafeArea())
        .navigationTitle("About")
    }

    private func url(_ string: String) -> URL {
        URL(string: string) ?? URL(string: "https://github.com/ewanc26/fathom")!
    }
}
