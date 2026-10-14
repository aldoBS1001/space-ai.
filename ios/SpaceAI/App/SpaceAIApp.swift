import SwiftUI
import SpaceAICore

@main
struct SpaceAIApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onAppear { model.refresh() }
                .onChange(of: phase) { p in if p == .active { model.refresh() } }
        }
    }
}

enum Route: Hashable {
    case results, review(CleanupCategory), confirm, report, compress, settings, folders, history
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(onResults: { path.append(.results) }, onSettings: { path.append(.settings) })
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .results:
                        ResultsView(open: { path.append(.review($0)) },
                                    reviewAll: { if model.preparePlan(nil) { path.append(.confirm) } else if let c = model.result?.categories.first { path.append(.review(c.category)) } },
                                    openFolders: { path.append(.folders) })
                    case .review(let c):
                        ReviewView(category: c, onDelete: { if model.preparePlan([c]) { path.append(.confirm) } },
                                   onCompress: { model.openCompression($0); path.append(.compress) })
                    case .confirm:
                        ConfirmView(onDone: { path.append(.report) }, onCancel: { model.pendingPlan = nil; path.removeLast() })
                    case .report:
                        ReportView(onDone: { path = [.results] })
                    case .compress:
                        CompressionView(onDeleteOriginal: {
                            if let item = model.compression?.item, model.planFor([item], risk: .medium, category: .compressibleVideos) { path.append(.confirm) }
                        })
                        .onDisappear { if !path.contains(.compress) { model.closeCompression() } }
                    case .settings:
                        SettingsView(openFolders: { path.append(.folders) }, openHistory: { path.append(.history) })
                    case .folders: FoldersView()
                    case .history: HistoryView()
                    }
                }
        }
        .tint(Brand.primary)
        .onChange(of: model.finishedScan) { done in
            if done { model.finishedScan = false; if path.last != .results { path.append(.results) } }
        }
        .alert(L("error_title"), isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
            Button(L("close"), role: .cancel) { model.errorMessage = nil }
        } message: { Text(model.errorMessage ?? "") }
    }
}
