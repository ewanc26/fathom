import SwiftUI
import FathomShared

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            FathomPalette.background.ignoresSafeArea()
            if model.identity == nil {
                ConnectView()
            } else {
                DashboardView()
            }
        }
    }
}

struct DashboardView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            NavigationStack { NowView() }
                .tabItem { Label("Now", systemImage: "waveform") }

            NavigationStack { StatsView() }
                .tabItem { Label("Stats", systemImage: "chart.bar") }

            NavigationStack { HistoryView() }
                .tabItem { Label("History", systemImage: "list.bullet") }

            NavigationStack { ProfileView() }
                .tabItem { Label("Profile", systemImage: "person.crop.circle") }
        }
        // One fetch feeds all four tabs; letting each tab load its own would
        // produce four different answers to the same question.
        .task { await model.start() }
        // A long read needs a quantity, not just motion: "reading" with no
        // count looks the same at four hundred plays as at twenty thousand,
        // and the difference is six seconds versus two minutes.
        .safeAreaInset(edge: .top, spacing: 0) {
            if model.loading, let plays = model.snapshot?.plays.count,
               !model.readingStatusDismissed {
                ReadingBar(playsRead: plays) { model.dismissReadingStatus() }
            }
        }
    }
}

private struct ReadingBar: View {
    let playsRead: Int
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            VStack(spacing: 4) {
                ProgressView()
                    .progressViewStyle(.linear)
                    .tint(FathomPalette.teal)
                Text(
                    "Reading history — "
                        + "\(FathomCore.shared.countText(value: Int32(playsRead))) plays so far"
                )
                .font(.caption2)
                .foregroundStyle(FathomPalette.muted)
            }

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(FathomPalette.muted)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss reading status")
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity)
        .background(FathomPalette.surface)
    }
}
