import SwiftUI
import AVKit
import UniformTypeIdentifiers
import SpaceAICore

// MARK: - Inicio

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    let onResults: () -> Void
    let onSettings: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack {
                    VStack(alignment: .leading) {
                        Text("SPACE AI").font(.title2.bold())
                        Text(L("tagline")).font(.subheadline).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(action: onSettings) { Image(systemName: "gearshape") }.accessibilityLabel(L("settings"))
                }
                StorageRing(snapshot: model.storage).padding(.vertical, 8)
                if let p = model.progress {
                    Card {
                        Text(L("phase_" + p.phase.rawValue) + (p.total > 1 ? " " + L("progress_count", p.done, p.total) : ""))
                            .accessibilityAddTraits(.updatesFrequently)
                        if p.total > 1 { ProgressView(value: p.fraction) } else { ProgressView().frame(maxWidth: .infinity) }
                        Text(L("scan_local_note")).font(.footnote).foregroundStyle(.secondary)
                        HStack { Spacer(); Button(L("cancel"), role: .cancel) { model.cancelScan() } }
                    }
                } else {
                    PrimaryButton(title: L("analyze")) { model.analyzeTapped() }
                    if model.result != nil { Button(L("see_last_results"), action: onResults) }
                    if model.access.photos == .denied {
                        Text(L("permission_denied_note_ios")).font(.footnote).multilineTextAlignment(.center).foregroundStyle(.secondary)
                        Button(L("open_settings")) { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                    }
                    if model.access.photos == .limited {
                        Text(L("permission_limited_note")).font(.footnote).multilineTextAlignment(.center).foregroundStyle(.secondary)
                    }
                }
                Text(L("privacy_footer")).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            .padding(22)
        }
        .background(Color(.systemGroupedBackground))
        .navigationBarHidden(true)
    }
}

// MARK: - Resultados

struct ResultsView: View {
    @EnvironmentObject var model: AppModel
    let open: (CleanupCategory) -> Void
    let reviewAll: () -> Void
    let openFolders: () -> Void
    @State private var showGuide = false

    var body: some View {
        if let r = model.result {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("potentially_recoverable")).font(.subheadline).foregroundStyle(.secondary)
                        Text(bytes(r.potentiallyRecoverableBytes)).font(.system(size: 34, weight: .bold, design: .rounded))
                        Text(L("estimate_disclaimer")).font(.footnote).foregroundStyle(.secondary)
                    }
                    StatRow(label: L("photos_to_review"), value: "\(photosToReview(r))")
                    StatRow(label: L("large_videos"), value: "\(r.category(.largeVideos)?.items.count ?? 0)")
                    StatRow(label: L("duplicate_files"), value: bytes(r.exactDuplicates.reduce(0) { $0 + $1.reclaimableBytes }))
                    StatRow(label: L("photos_total"), value: "\(r.photoCount) · \(bytes(r.photoBytes))")
                    StatRow(label: L("videos_total"), value: "\(r.videoCount) · \(bytes(r.videoBytes))")
                    StatRow(label: L("files_total"), value: "\(r.fileCount) · \(bytes(r.fileBytes))")
                    if !r.unreadable.isEmpty { StatRow(label: L("unreadable_ios"), value: "\(r.unreadable.count)") }
                }
                Section { PrimaryButton(title: L("review_and_free"), enabled: !r.categories.isEmpty, action: reviewAll) }
                    .listRowBackground(Color.clear).listRowInsets(EdgeInsets())

                if !r.recommendations.isEmpty {
                    Section(L("smart_recommendations")) {
                        ForEach(r.recommendations, id: \.category) { rec in
                            Button { rec.category == .appTempFiles ? model.clearTemp() : open(rec.category) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack { Text(categoryTitle(rec.category)).font(.headline); Spacer(); RiskBadge(risk: rec.risk) }
                                    Text(recText(rec)).font(.subheadline).foregroundStyle(.secondary)
                                }
                            }.buttonStyle(.plain)
                        }
                    }
                }
                Section(L("all_categories")) {
                    ForEach(r.categories, id: \.category) { c in
                        Button { open(c.category) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(categoryTitle(c.category)).font(.headline)
                                    Text(L(c.isEstimate ? "count_bytes_estimate" : "count_bytes", c.items.count, bytes(c.bytes)))
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                                Spacer(); RiskBadge(risk: c.risk)
                            }
                        }.buttonStyle(.plain)
                    }
                }
                Section {
                    Button(action: openFolders) {
                        VStack(alignment: .leading) { Text(L("files_folders_title")).font(.headline); Text(L("files_folders_body")).font(.subheadline) }
                    }.buttonStyle(.plain)
                }
                Section(L("access_title")) {
                    StatRow(label: L("access_photos"), value: accessLabel(r.access.photos))
                    StatRow(label: L("access_files"), value: accessLabel(r.access.files))
                    StatRow(label: L("access_other_caches"), value: accessLabel(r.access.otherAppsCaches))
                    Button(L("other_apps_guide_button")) { showGuide = true }
                }
                Section(L("cat_appTempFiles")) {
                    Text(bytes(r.appTempBytes))
                    if let f = model.tempFreed { Text(L("temp_freed", bytes(f))).font(.footnote) }
                    Button(L("clear_temp")) { model.clearTemp() }.disabled(r.appTempBytes == 0)
                }
            }
            .navigationTitle("SPACE AI")
            .alert(L("other_apps_guide_title"), isPresented: $showGuide) {
                Button(L("close"), role: .cancel) {}
            } message: { Text(L("other_apps_guide_body_ios")) }
        }
    }

    private func photosToReview(_ r: ScanResult) -> Int {
        [CleanupCategory.similarPhotos, .burstPhotos, .blurryPhotos, .darkPhotos, .screenshots].reduce(0) { $0 + (r.category($1)?.items.count ?? 0) }
    }

    private func recText(_ rec: Recommendation) -> String {
        switch rec.category {
        case .compressibleVideos: return L("rec_compress", bytes(rec.bytes), categoryTitle(rec.category).lowercased())
        case .appTempFiles: return L("rec_temp", bytes(rec.bytes))
        default: return L("rec_generic", bytes(rec.bytes), categoryTitle(rec.category).lowercased())
        }
    }
}

// MARK: - Revisión

struct ReviewView: View {
    @EnvironmentObject var model: AppModel
    let category: CleanupCategory
    let onDelete: () -> Void
    let onCompress: (MediaItem) -> Void
    @State private var sort = 0

    private var groups: [[MediaItem]] {
        guard let r = model.result else { return [] }
        switch category {
        case .exactDuplicatePhotos: return r.exactDuplicates.filter { $0.items.allSatisfy { $0.kind == .photo } }.map(\.items)
        case .duplicateVideos: return r.exactDuplicates.filter { $0.items.allSatisfy { $0.kind == .video } }.map(\.items)
        case .duplicateFiles: return r.exactDuplicates.filter { !$0.items.contains { $0.kind == .photo || $0.kind == .video } }.map(\.items)
        case .similarPhotos: return r.similarGroups.filter { !$0.isBurst }.map { $0.items.map(\.item) }
        case .burstPhotos: return r.similarGroups.filter(\.isBurst).map { $0.items.map(\.item) }
        default: return []
        }
    }

    private var flat: [MediaItem] {
        let items = model.result?.category(category)?.items ?? []
        switch sort {
        case 1: return items.sorted { ($0.dateTaken > 0 ? $0.dateTaken : $0.dateModified) < ($1.dateTaken > 0 ? $1.dateTaken : $1.dateModified) }
        case 2: return items.sorted { ($0.mimeType ?? "") < ($1.mimeType ?? "") }
        default: return items.sorted { $0.sizeBytes > $1.sizeBytes }
        }
    }

    private var isList: Bool { [.largeVideos, .oldVideos, .compressibleVideos, .screenRecordings, .largeFiles, .duplicateFiles].contains(category) }

    var body: some View {
        let selectable = (groups.isEmpty ? flat : groups.flatMap { $0 }).filter { !model.keep.contains($0.id) }
        let selectedHere = selectable.filter { model.selected.contains($0.id) }
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack { Text(categoryTitle(category)).font(.title2.bold()); Spacer(); if let c = model.result?.category(category) { RiskBadge(risk: c.risk) } }
                    Text(L(groups.isEmpty ? "review_hint" : "review_hint_groups_ios")).font(.subheadline).foregroundStyle(.secondary)
                    if isList {
                        Picker("", selection: $sort) { Text(L("sort_size")).tag(0); Text(L("sort_date")).tag(1); Text(L("sort_type")).tag(2) }
                            .pickerStyle(.segmented)
                    }
                    HStack {
                        Button(L("select_all")) { model.selectAll(selectable.map(\.id), true) }
                        Button(L("select_none")) { model.selectAll(selectable.map(\.id), false) }
                    }
                    if !groups.isEmpty && !isList {
                        ForEach(Array(groups.enumerated()), id: \.offset) { gi, g in
                            Text(L("group_n", gi + 1, g.count)).font(.headline).padding(.top, 6)
                            grid(g, group: g.map(\.id))
                        }
                    } else if isList {
                        ForEach(groups.isEmpty ? flat : groups.flatMap { $0 }, id: \.id) { row($0) }
                    } else {
                        grid(flat, group: nil)
                    }
                }.padding(16)
            }
            PrimaryButton(title: L("delete_selected", selectedHere.count, bytes(selectedHere.reduce(0) { $0 + $1.sizeBytes })),
                          enabled: !selectedHere.isEmpty, action: onDelete)
                .padding(16).background(.bar)
        }
        .navigationTitle(categoryTitle(category))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func grid(_ items: [MediaItem], group: [String]?) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8)], spacing: 8) {
            ForEach(items, id: \.id) { item in
                let sel = model.selected.contains(item.id), kept = model.keep.contains(item.id)
                AssetThumb(item: item)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(sel ? Brand.high : .clear, lineWidth: 3))
                    .overlay(alignment: .topLeading) { if kept { Image(systemName: "star.fill").foregroundStyle(Brand.primary).padding(4) } }
                    .overlay(alignment: .topTrailing) { if sel { Image(systemName: "checkmark.circle.fill").foregroundStyle(Brand.high).padding(4) } }
                    .overlay(alignment: .bottomLeading) { Text(bytes(item.sizeBytes)).font(.caption2).foregroundStyle(.white).padding(5).shadow(radius: 2) }
                    .onTapGesture { model.toggle(item.id) }
                    .onLongPressGesture { if let group { model.setKeeper(group: group, keeper: item.id) } }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityValue(L(kept ? "state_keep" : (sel ? "state_selected" : "state_not_selected")))
                    .accessibilityAction(named: L("keep_this")) { if let group { model.setKeeper(group: group, keeper: item.id) } }
            }
        }
    }

    private func row(_ item: MediaItem) -> some View {
        let sel = model.selected.contains(item.id)
        let date = (item.dateTaken > 0 ? item.dateTaken : item.dateModified)
        return HStack(spacing: 10) {
            Button { model.toggle(item.id) } label: {
                Image(systemName: sel ? "checkmark.circle.fill" : "circle").font(.title3).foregroundStyle(sel ? Brand.high : .secondary)
            }.disabled(model.keep.contains(item.id) || !item.deletable)
            if item.kind == .video { AssetThumb(item: item).frame(width: 64, height: 64) }
            VStack(alignment: .leading) {
                Text(item.displayName).lineLimit(1)
                Text("\(bytes(item.sizeBytes)) · \(date > 0 ? Date(timeIntervalSince1970: Double(date) / 1000).formatted(date: .abbreviated, time: .omitted) : "—")")
                    .font(.caption).foregroundStyle(.secondary)
                if !item.deletable { Text(L("not_deletable_ios")).font(.caption).foregroundStyle(Brand.high) }
            }
            Spacer()
            if item.kind == .video { Button(L("compress")) { onCompress(item) }.buttonStyle(.bordered) }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Confirmación y reporte

struct ConfirmView: View {
    @EnvironmentObject var model: AppModel
    let onDone: () -> Void
    let onCancel: () -> Void
    @State private var ack = false

    var body: some View {
        if let plan = model.pendingPlan {
            VStack(spacing: 0) {
                List {
                    Section {
                        HStack { Text(L("confirm_title")).font(.title3.bold()); Spacer(); RiskBadge(risk: plan.maxRisk) }
                        StatRow(label: L("files_count"), value: "\(plan.count)")
                        StatRow(label: L("potentially_recoverable"), value: bytes(plan.totalBytes))
                        Text(L("mode_recently_deleted_ios")).font(.footnote).foregroundStyle(.secondary)
                        Text(L("icloud_warning_ios")).font(.footnote).foregroundStyle(Brand.high)
                    }
                    Section {
                        ForEach(plan.items, id: \.item.id) { p in
                            HStack {
                                VStack(alignment: .leading) { Text(p.item.displayName).lineLimit(1); Text(categoryTitle(p.category)).font(.caption).foregroundStyle(.secondary) }
                                Spacer(); Text(bytes(p.item.sizeBytes)).font(.subheadline)
                            }
                        }
                    }
                }
                VStack(spacing: 10) {
                    if plan.requiresExplicitApproval { Toggle(L("ack_text", plan.count), isOn: $ack).font(.subheadline) }
                    if model.cleaning {
                        HStack { ProgressView(); Text(L("cleaning")); Spacer(); Button(L("cancel")) { model.cancelCleanup() } }
                    } else {
                        PrimaryButton(title: L("delete_n_ios", plan.count), enabled: !plan.requiresExplicitApproval || ack) {
                            model.execute(approved: ack, onDone: onDone)
                        }
                        Button(L("cancel"), role: .cancel, action: onCancel)
                    }
                }.padding(16).background(.bar)
            }
            .navigationTitle(L("confirm_title"))
        } else {
            Text(L("nothing_selected"))
        }
    }
}

struct ReportView: View {
    @EnvironmentObject var model: AppModel
    let onDone: () -> Void
    var body: some View {
        if let r = model.lastReport {
            List {
                Section {
                    Text(L("status_" + r.status.rawValue)).font(.title3.bold())
                    Text(L("status_" + r.status.rawValue + "_body")).font(.subheadline)
                    StatRow(label: L("measured_freed"), value: bytes(max(0, r.measuredFreedBytes)))
                    StatRow(label: L("deleted_count"), value: "\(r.deletedCount) · \(bytes(r.deletedBytes))")
                    StatRow(label: L("recently_deleted_count"), value: "\(r.trashedCount) · \(bytes(r.trashedBytes))")
                    StatRow(label: L("failed_count"), value: "\(r.failed.count)")
                    StatRow(label: L("denied_count"), value: "\(r.results.filter { $0.outcome == .userDenied }.count)")
                    StatRow(label: L("free_now"), value: bytes(r.after.freeBytes))
                }
                if r.trashedCount > 0 { Section { Text(L("recently_deleted_explainer")).font(.subheadline) } }
                if !r.failed.isEmpty {
                    Section(L("failures")) { ForEach(r.failed, id: \.item.id) { Text("\($0.item.displayName): \($0.error ?? "—")").font(.footnote) } }
                }
                Section { PrimaryButton(title: L("done"), action: onDone) }.listRowBackground(Color.clear)
            }
            .navigationBarBackButtonHidden(true)
        }
    }
}

// MARK: - Compresión de video

struct CompressionView: View {
    @EnvironmentObject var model: AppModel
    let onDeleteOriginal: () -> Void

    var body: some View {
        ScrollView {
            if let c = model.compression {
                VStack(alignment: .leading, spacing: 14) {
                    Text(c.item.displayName).font(.title3.bold())
                    if let info = c.info, let est = try? VideoCompressionPlanner.estimate(info, preset: c.preset) {
                        Card {
                            StatRow(label: L("original_size"), value: bytes(info.sizeBytes))
                            StatRow(label: L("original_res"), value: "\(info.width)×\(info.height)")
                            StatRow(label: L("result_res"), value: "≈ \(est.targetWidth)×\(est.targetHeight)")
                            StatRow(label: L("estimated_size"), value: bytes(est.estimatedBytes))
                            StatRow(label: L("estimated_savings"), value: "\(bytes(est.estimatedSavingsBytes)) (\(Int(est.savingsFraction * 100)) %)")
                            if !est.worthIt { Text(L("not_worth_it")).font(.footnote).foregroundStyle(Brand.high) }
                        }
                        Picker(L("compression_quality"), selection: Binding(get: { c.preset }, set: { model.setPreset($0) })) {
                            Text(L("preset_high")).tag(CompressionPreset.high)
                            Text(L("preset_balanced")).tag(CompressionPreset.balanced)
                            Text(L("preset_small")).tag(CompressionPreset.small)
                        }.pickerStyle(.segmented).disabled(c.progress != nil)
                        content(c, info: info, estimate: est)
                    } else if c.info == nil {
                        ProgressView(L("reading_video_ios"))
                    }
                }.padding(18)
            }
        }
        .navigationTitle(L("compress"))
    }

    @ViewBuilder private func content(_ c: CompressionUI, info: VideoInfo, estimate: CompressionEstimate) -> some View {
        if let p = c.progress {
            Text(L("compressing", Int(p * 100)))
            ProgressView(value: p)
            Button(L("cancel"), role: .cancel) { model.cancelCompression() }
        } else if let job = c.job {
            if let reason = invalidReason(job.validation) {
                Text(L("compression_invalid", reason.rawValue)).foregroundStyle(Brand.high)
                Text(L("original_untouched"))
            } else if !c.savedCopy {
                Card {
                    StatRow(label: L("real_result_size"), value: bytes(job.output.sizeBytes))
                    StatRow(label: L("real_result_res"), value: "\(job.output.width)×\(job.output.height)")
                    StatRow(label: L("real_savings"), value: bytes(info.sizeBytes - job.output.sizeBytes))
                }
                Text(L("watch_before_keeping")).font(.subheadline)
                PreviewPlayer(url: job.outputURL) { model.compression?.previewWatched = true }
                    .aspectRatio(CGFloat(max(job.output.width, 1)) / CGFloat(max(job.output.height, 1)), contentMode: .fit)
                PrimaryButton(title: L("keep_copy"), enabled: c.previewWatched) { model.saveCopy() }
                Button(L("discard_copy"), role: .destructive) { model.discardCopy() }
            } else {
                Text(L("copy_saved_ios"))
                PrimaryButton(title: L("review_delete_original"), action: onDeleteOriginal)
            }
        } else {
            PrimaryButton(title: L("compress"), enabled: estimate.worthIt) { model.startCompression() }
        }
    }
}

private func invalidReason(_ v: CompressionValidation) -> CompressionValidation.Reason? {
    if case .invalid(let r) = v { return r }
    return nil
}

/// Reproductor de la copia; marca "vista" cuando realmente empieza a reproducirse.
struct PreviewPlayer: View {
    let url: URL
    let onPlayed: () -> Void
    @State private var player: AVPlayer?
    @State private var observer: NSKeyValueObservation?
    var body: some View {
        VideoPlayer(player: player)
            .onAppear {
                let p = AVPlayer(url: url)
                observer = p.observe(\.timeControlStatus) { pl, _ in if pl.timeControlStatus == .playing { DispatchQueue.main.async { onPlayed() } } }
                player = p
                p.play()
            }
            .onDisappear { player?.pause(); observer = nil }
    }
}

// MARK: - Configuración, carpetas, historial

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    let openFolders: () -> Void
    let openHistory: () -> Void
    var body: some View {
        Form {
            Section(header: Text(L("sensitivity")), footer: Text(L("sensitivity_help"))) {
                Picker(L("sensitivity"), selection: $model.sensitivityRaw) {
                    Text(L("sens_strict")).tag(Sensitivity.strict.rawValue)
                    Text(L("sens_normal")).tag(Sensitivity.normal.rawValue)
                    Text(L("sens_relaxed")).tag(Sensitivity.relaxed.rawValue)
                }.pickerStyle(.inline).labelsHidden()
                Toggle(L("conservative_quality"), isOn: $model.conservative)
            }
            Section(L("compression_quality")) {
                Picker(L("compression_quality"), selection: $model.presetRaw) {
                    Text(L("preset_high")).tag(CompressionPreset.high.rawValue)
                    Text(L("preset_balanced")).tag(CompressionPreset.balanced.rawValue)
                    Text(L("preset_small")).tag(CompressionPreset.small.rawValue)
                }.pickerStyle(.inline).labelsHidden()
            }
            Section {
                Button(L("files_folders_title"), action: openFolders)
                Button(L("history"), action: openHistory)
                Button(L("clear_analysis_cache")) { model.clearAnalysisCache() }
                Button(L("clear_history"), role: .destructive) { model.clearHistory() }
            }
            Section(L("privacy_title")) { Text(L("privacy_body")).font(.footnote) }
        }
        .navigationTitle(L("settings"))
    }
}

struct FoldersView: View {
    @EnvironmentObject var model: AppModel
    @State private var picking = false
    @State private var urls: [URL] = []
    var body: some View {
        List {
            Section(footer: Text(L("folders_help_ios"))) {
                ForEach(urls, id: \.self) { u in
                    HStack { Text(u.lastPathComponent); Spacer()
                        Button(L("remove_access"), role: .destructive) { model.service.folders.remove(u); reload() }.buttonStyle(.borderless) }
                }
            }
            Section { Button(L("add_folder")) { picking = true } }
        }
        .navigationTitle(L("files_folders_title"))
        .onAppear(perform: reload)
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { try? model.service.folders.add(url); reload(); model.refresh() }
        }
    }
    private func reload() { urls = model.service.folders.urls }
}

struct HistoryView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        List {
            if model.history.isEmpty { Text(L("history_empty")) }
            ForEach(model.history) { h in
                VStack(alignment: .leading, spacing: 4) {
                    Text(Date(timeIntervalSince1970: h.timestamp).formatted(date: .abbreviated, time: .shortened)).font(.headline)
                    StatRow(label: L("measured_freed"), value: bytes(max(0, h.measuredFreedBytes)))
                    StatRow(label: L("deleted_count"), value: "\(h.deletedCount) · \(bytes(h.deletedBytes))")
                    StatRow(label: L("recently_deleted_count"), value: "\(h.trashedCount) · \(bytes(h.trashedBytes))")
                    StatRow(label: L("failed_count"), value: "\(h.failedCount)")
                }
            }
        }
        .navigationTitle(L("history"))
        .onAppear { model.loadHistory() }
    }
}
