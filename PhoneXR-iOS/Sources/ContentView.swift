import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @EnvironmentObject private var library: GameLibrary
    @State private var importing = false
    @State private var showingHands = false

    private var packageTypes: [UTType] {
        [UTType(filenameExtension: "apk"), .zip, UTType(filenameExtension: "pxr")].compactMap { $0 }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button { importing = true } label: {
                        Label("Import a .pxr, an APK or a ZIP", systemImage: "square.and.arrow.down")
                    }
                    Button { showingHands = true } label: {
                        Label("Check hand tracking", systemImage: "hand.raised")
                    }
                } header: {
                    Text("PhoneXR for iPhone")
                } footer: {
                    Text("The APK is stored on the iPhone and analyzed. Android code does not run as iOS code: a game needs a port from source.")
                }

                Section("Library") {
                    if library.games.isEmpty {
                        ContentUnavailableView("No games yet", systemImage: "visionpro", description: Text("Import an OpenXR or Quest APK"))
                    }
                    ForEach(library.games) { game in
                        NavigationLink {
                            GameDetails(game: game)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(game.report.title).font(.headline)
                                Text(game.report.hasOpenXR ? "OpenXR / Quest" : "Archive")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete(perform: library.remove)
                }

                Section { Text(library.message).foregroundStyle(.secondary) }
            }
            .navigationTitle("PhoneXR")
            .fileImporter(isPresented: $importing, allowedContentTypes: packageTypes) { result in
                if case let .success(url) = result { library.importPackage(url) }
                if case let .failure(error) = result { library.message = error.localizedDescription }
            }
            .fullScreenCover(isPresented: $showingHands) {
                HandTrackingView().ignoresSafeArea()
            }
        }
    }
}

private struct GameDetails: View {
    let game: ImportedGame

    var body: some View {
        List {
            Section("Package check") {
                ForEach(game.report.details, id: \.self) { detail in
                    Label(detail, systemImage: detail.contains("No ") || detail.contains("needs") ? "exclamationmark.triangle" : "checkmark.circle")
                }
            }
            Section {
                Button("Launch") { }
                    .disabled(!game.report.canLaunchNatively)
            } footer: {
                Text(game.report.canLaunchNatively ? "The native PhoneXR package is ready." : "This APK holds Android binaries. The game has to be rebuilt for iOS/Metal.")
            }
        }
        .navigationTitle(game.report.title)
    }
}
