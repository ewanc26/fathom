import SwiftUI
import FathomShared

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
                // The OAuth redirect (issue #8), registered in project.yml's
                // CFBundleURLTypes. Parsing and retention live in the shared
                // core; the result waits for the login flow (issue #9) to
                // claim it, so no successful login is ever asserted here.
                .onOpenURL { url in
                    _ = FathomCore.shared.receiveOAuthCallback(url: url.absoluteString)
                }
        }
    }
}
