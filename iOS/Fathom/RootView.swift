import SwiftUI

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
    }
}
