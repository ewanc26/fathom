import SwiftUI

/// The first screen, and the only one that needs typing.
///
/// No sign-in step exists because there is nothing to authorise: teal.fm
/// records are public, and asking for a password to read a public repo would
/// be theatre.
struct ConnectView: View {
    @EnvironmentObject private var model: AppModel
    @State private var input = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Fathom")
                    .font(.largeTitle.weight(.bold))
                    .foregroundStyle(FathomPalette.teal)

                Text("Your teal.fm listening, measured.")
                    .font(.title3)
                    .foregroundStyle(FathomPalette.ink)

                Text("Fathom reads the plays already in your AT Protocol repo and works the figures out on the device. Nothing is uploaded, and no account password is needed.")
                    .font(.subheadline)
                    .foregroundStyle(FathomPalette.muted)

                TextField("you.example", text: $input)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .padding(14)
                    .background(FathomPalette.surface)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .disabled(model.connecting)
                    .padding(.top, 10)

                Text("Handle or DID")
                    .font(.caption)
                    .foregroundStyle(FathomPalette.muted)

                Button {
                    Task { await model.connect(input) }
                } label: {
                    Text(model.connecting ? "Looking up…" : "Open dashboard")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.connecting || input.trimmingCharacters(in: .whitespaces).isEmpty)

                if let error = model.error {
                    NoticeBox(text: error)
                }

                Text("Reads fm.teal.feed.play and the older fm.teal.alpha.feed.play directly from your data server.")
                    .font(.caption2)
                    .foregroundStyle(FathomPalette.muted)
                    .padding(.top, 8)
            }
            .padding(24)
        }
        .background(FathomPalette.background.ignoresSafeArea())
    }
}
