import SwiftUI

@main
struct FathomApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                // Fathom is dark-only on both platforms. The palette is built
                // against a deep surface; a light mode would be a second design.
                .preferredColorScheme(.dark)
                .tint(FathomPalette.teal)
        }
    }
}
