import SwiftUI
import SpaceAICore

enum Brand {
    static let primary = Color(red: 0.18, green: 0.36, blue: 1.0)
    static let soft = Color(red: 0.36, green: 0.55, blue: 1.0)
    static let low = Color(red: 0.12, green: 0.62, blue: 0.42)
    static let medium = Color(red: 0.89, green: 0.60, blue: 0.07)
    static let high = Color(red: 0.84, green: 0.27, blue: 0.27)
}

/// Anillo animado con la ocupación REAL medida del volumen.
struct StorageRing: View {
    let snapshot: StorageSnapshot?
    @State private var shown: Double = 0

    var body: some View {
        let target = snapshot?.usedFraction ?? 0
        ZStack {
            Circle().stroke(Color(.tertiarySystemFill), style: StrokeStyle(lineWidth: 22, lineCap: .round))
            Circle().trim(from: 0, to: shown)
                .stroke(AngularGradient(colors: [Brand.soft, Brand.primary, Brand.soft], center: .center),
                        style: StrokeStyle(lineWidth: 22, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 4) {
                if let s = snapshot {
                    Text(bytes(s.freeBytes)).font(.system(size: 34, weight: .bold, design: .rounded)).minimumScaleFactor(0.6)
                    Text(L("free_of", bytes(s.totalBytes))).font(.subheadline).foregroundStyle(.secondary)
                } else {
                    Text(L("measuring")).font(.body)
                }
            }
        }
        .frame(width: 230, height: 230)
        .onAppear { withAnimation(.easeInOut(duration: 1.1)) { shown = target } }
        .onChange(of: target) { t in withAnimation(.easeInOut(duration: 1.1)) { shown = t } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(snapshot.map { L("a11y_ring", bytes($0.usedBytes), bytes($0.totalBytes), bytes($0.freeBytes)) } ?? L("measuring"))
    }
}

struct RiskBadge: View {
    let risk: RiskLevel
    private var style: (Color, String) {
        switch risk {
        case .low: return (Brand.low, "risk_low")
        case .medium: return (Brand.medium, "risk_medium")
        case .high: return (Brand.high, "risk_high")
        }
    }
    var body: some View {
        Text(L(style.1)).font(.caption.weight(.semibold)).foregroundStyle(style.0)
            .padding(.horizontal, 10).padding(.vertical, 3)
            .background(style.0.opacity(0.14), in: Capsule())
    }
}

struct PrimaryButton: View {
    let title: String
    var enabled = true
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title.uppercased()).font(.headline).tracking(0.6).frame(maxWidth: .infinity, minHeight: 58)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 18))
        .disabled(!enabled)
    }
}

struct Card<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

struct StatRow: View {
    let label: String, value: String
    var body: some View {
        HStack { Text(label).foregroundStyle(.secondary); Spacer(); Text(value) }.font(.subheadline)
            .accessibilityElement(children: .combine)
    }
}

struct AssetThumb: View {
    @EnvironmentObject var model: AppModel
    let item: MediaItem
    @State private var image: UIImage?
    var body: some View {
        Color(.tertiarySystemFill)
            .aspectRatio(1, contentMode: .fit)
            .overlay { if let image { Image(uiImage: image).resizable().scaledToFill() } }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .task(id: item.id) { image = await model.service.library.thumbnail(for: item.id, side: 300) }
            .accessibilityLabel(item.displayName)
    }
}

func categoryTitle(_ c: CleanupCategory) -> String { L("cat_" + c.rawValue) }

func accessLabel(_ a: AccessState) -> String { L("access_" + a.rawValue) }

/// Firma de la empresa: logotipo de Business Shop (el asset cambia solo entre modo claro y oscuro).
struct BrandFooter: View {
    var logoHeight: CGFloat = 64
    var body: some View {
        VStack(spacing: 4) {
            Image("BusinessShopLogo").resizable().scaledToFit().frame(height: logoHeight)
                .accessibilityLabel(L("logo_a11y"))
            Text(L("made_by")).font(.footnote).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}
