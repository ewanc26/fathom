import SwiftUI
import FathomShared

/// Mirrors ui/theme/Theme.kt on Android. The two must stay recognisably the
/// same product, so the values are kept in one place per platform and changed
/// together.
enum FathomPalette {
    static let background = Color(red: 0.043, green: 0.078, blue: 0.086)
    static let surface = Color(red: 0.071, green: 0.125, blue: 0.141)
    static let surfaceHigh = Color(red: 0.102, green: 0.180, blue: 0.196)
    static let teal = Color(red: 0.176, green: 0.831, blue: 0.749)
    static let tealDeep = Color(red: 0.059, green: 0.463, blue: 0.431)
    static let ink = Color(red: 0.906, green: 0.957, blue: 0.945)
    static let muted = Color(red: 0.549, green: 0.651, blue: 0.639)
}

/// Every block on the dashboard.
struct Panel<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FathomPalette.surface)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

struct StatTile: View {
    let value: String
    let label: String
    var detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.title2.weight(.semibold))
                .foregroundStyle(FathomPalette.ink)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
            Text(label)
                .font(.caption)
                .foregroundStyle(FathomPalette.muted)
            if let detail {
                Text(detail)
                    .font(.caption2)
                    .foregroundStyle(FathomPalette.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct RankRow: View {
    let rank: Int
    let title: String
    var subtitle: String?
    let playCount: Int32
    let fraction: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Text("\(rank)")
                    .font(.caption)
                    .foregroundStyle(FathomPalette.muted)
                    .frame(width: 20, alignment: .leading)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.body)
                        .lineLimit(1)
                    if let subtitle {
                        Text(subtitle)
                            .font(.caption2)
                            .foregroundStyle(FathomPalette.muted)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Text("\(playCount)")
                    .font(.caption)
                    .foregroundStyle(FathomPalette.teal)
            }
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(FathomPalette.surfaceHigh)
                    Capsule()
                        .fill(FathomPalette.teal)
                        .frame(width: max(0, geometry.size.width * min(fraction, 1)))
                }
            }
            .frame(height: 3)
            .padding(.leading, 30)
        }
        .padding(.vertical, 6)
    }
}

/// One play, with the time it happened and the length the record declared.
struct PlayRowView: View {
    let play: Play
    let nowEpochSeconds: Int64
    let tzOffsetMinutes: Int32

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(play.title())
                    .font(.body)
                    .lineLimit(1)
                Text(play.artistLine())
                    .font(.subheadline)
                    .foregroundStyle(FathomPalette.muted)
                    .lineLimit(1)

                let footnotes = [timeText, play.serviceLabel()]
                    .compactMap { $0 }
                    .joined(separator: " · ")
                if !footnotes.isEmpty {
                    Text(footnotes)
                        .font(.caption2)
                        .foregroundStyle(FathomPalette.muted)
                }
            }
            Spacer(minLength: 8)
            Text(play.durationText())
                .font(.caption)
                .foregroundStyle(FathomPalette.muted)
        }
        .padding(.vertical, 6)
    }

    private var timeText: String? {
        let instant = FathomCore.shared.instant(raw: play.playedTime)
        guard instant != Int64.min else { return nil }
        return FathomCore.shared.relativeTime(
            epochSeconds: instant,
            nowEpochSeconds: nowEpochSeconds,
            tzOffsetMinutes: tzOffsetMinutes
        )
    }
}

/// A monogram, because Fathom deliberately fetches no avatar blobs.
struct Monogram: View {
    let name: String
    var size: CGFloat = 52

    private var initials: String {
        let letters = name
            .split(whereSeparator: { $0 == " " || $0 == "." || $0 == "-" || $0 == "_" })
            .prefix(2)
            .compactMap { $0.first }
            .map { String($0).uppercased() }
            .joined()
        return letters.isEmpty ? "?" : letters
    }

    var body: some View {
        ZStack {
            Circle().fill(FathomPalette.tealDeep)
            Text(initials)
                .font(.headline)
                .foregroundStyle(FathomPalette.ink)
        }
        .frame(width: size, height: size)
    }
}

struct NoticeBox: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(FathomPalette.muted)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FathomPalette.surfaceHigh)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// A stored property called `body` would collide with `View.body`, so the
/// explanation is held as `detail` and the initialiser keeps the `body:`
/// label every call site already reads.
struct EmptyBlock: View {
    let title: String
    private let detail: String

    init(title: String, body: String) {
        self.title = title
        self.detail = body
    }

    var body: some View {
        VStack(spacing: 6) {
            Text(title).font(.headline)
            Text(detail)
                .font(.subheadline)
                .foregroundStyle(FathomPalette.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
    }
}

struct SectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(FathomPalette.ink)
    }
}

/// Bars for the recent days, with an empty day drawn as a stub rather than a
/// gap so the chart cannot be read as missing data.
struct DayChart: View {
    let days: [DayBucket]

    private var busiest: Int {
        max(Int(days.map { Int($0.playCount) }.max() ?? 0), 1)
    }

    private func barHeight(_ count: Int) -> CGFloat {
        6 + CGFloat(count) / CGFloat(busiest) * 54
    }

    var body: some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(Array(days.enumerated()), id: \.offset) { _, day in
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(day.playCount > 0 ? FathomPalette.teal : FathomPalette.surfaceHigh)
                    .frame(maxWidth: .infinity)
                    .frame(height: barHeight(Int(day.playCount)))
            }
        }
        .frame(height: 64, alignment: .bottom)
    }
}
