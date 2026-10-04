import SwiftUI
import AppKit

#if PHONEXR_LITE
private let productName = "NextVR Lite Share"
#else
private let productName = "NextVR Share"
#endif

@main
struct PhoneXRShareApp: App {
    @StateObject private var model = ShareModel()
    var body: some Scene {
        WindowGroup(productName) { ShareView().environmentObject(model).frame(minWidth: 760, minHeight: 540) }
            .windowStyle(.titleBar).defaultSize(width: 900, height: 650)
    }
}

struct ShareView: View {
    @EnvironmentObject var model: ShareModel
    @StateObject private var stream = ScreenStream()
    var body: some View {
        NavigationSplitView {
            List(selection: $model.page) {
                Label("Screen sharing", systemImage: "display.and.arrow.down").tag(SharePage.screen)
                Label("VR control", systemImage: "hand.point.up.left").tag(SharePage.remote)
                Label("NextVR for Android", systemImage: "visionpro").tag(SharePage.android)
            }.navigationTitle(productName)
        } detail: {
            switch model.page {
            case .screen: screenPage
            case .remote: remotePage
            case .android: androidPage
            }
        }
        .onAppear {
            if let url = Bundle.main.url(forResource: "PhoneXRShare", withExtension: "png"),
               let icon = NSImage(contentsOf: url) { NSApp.applicationIconImage = icon }
        }
    }

    private var screenPage: some View {
        page("Screen sharing", "Show the Mac screen in a spatial NextVR window.") {
            GroupBox {
                VStack(alignment: .leading, spacing: 16) {
                    Label(stream.status, systemImage: stream.running ? "dot.radiowaves.left.and.right" : "display")
                    Button(stream.running ? "Stop sharing" : "Share the screen") { stream.toggle() }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                    Text("The Mac and the phone have to be on the same local network. On the first run, allow screen recording.")
                        .font(.callout).foregroundStyle(.secondary)
                }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
            }
            #if PHONEXR_LITE
            Label("Lite: 10 frames/s, a reduced resolution and JPEG 55% for slower Macs.", systemImage: "leaf.fill")
                .foregroundStyle(.green)
            #else
            Text("The full version streams up to 20 frames/s at a higher quality.").foregroundStyle(.secondary)
            #endif
        }
    }

    private var remotePage: some View {
        page("VR control", "The VR home windows on the Mac screen: the browser, apps and games — with a mouse and a keyboard.") {
            if model.devices.isEmpty {
                Text("First connect the phone over USB on the “NextVR for Android” page.").foregroundStyle(.secondary)
            }
            VrRemoteView()
        }
    }

    private var androidPage: some View {
        page("NextVR for Android", "The latest APK is already inside the app.") {
            GroupBox("Connected phone") {
                VStack(alignment: .leading, spacing: 14) {
                    Picker("Device", selection: $model.selectedSerial) {
                        Text("No phone selected").tag(nil as String?)
                        ForEach(model.devices) { Text($0.model).tag($0.serial as String?) }
                    }
                    HStack {
                        Button("Refresh the list") { model.refresh() }
                        Button("Install the new NextVR") { model.install() }
                            .buttonStyle(.borderedProminent).disabled(!model.canInstall)
                    }
                    Text(model.status).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                }.padding(10)
            }
            Text("On the phone turn on “Developer options → USB debugging”, plug in a data cable and confirm the RSA key.")
                .font(.callout).foregroundStyle(.secondary)
        }
    }

    private func page<Content: View>(_ title: String, _ subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        ScrollView { VStack(alignment: .leading, spacing: 20) {
            Text(title).font(.largeTitle.bold()); Text(subtitle).font(.title3).foregroundStyle(.secondary); content()
        }.padding(30).frame(maxWidth: 1100, alignment: .leading) }.navigationTitle(title)
    }
}

enum SharePage: Hashable { case screen, remote, android }

struct Device: Identifiable, Hashable {
    let serial: String; let model: String; let state: String
    var id: String { serial }
    var isReady: Bool { state == "device" }
}

@MainActor
final class ShareModel: ObservableObject {
    @Published var page: SharePage = .screen
    @Published var devices: [Device] = []
    @Published var selectedSerial: String?
    @Published var status = "Connect an Android device over USB."
    @Published var busy = false
    let bridge = AndroidBridge()
    var canInstall: Bool { !busy && devices.first { $0.serial == selectedSerial }?.isReady == true }

    init() { refresh() }
    func refresh() {
        busy = true; status = "Looking for Android…"; let bridge = bridge
        Task.detached {
            let result = Result { try bridge.devices() }
            await MainActor.run {
                self.busy = false
                switch result {
                case .success(let found):
                    self.devices = found; if self.selectedSerial == nil { self.selectedSerial = found.first?.serial }
                    self.status = found.isEmpty ? "No Android device found." : "Phone found. NextVR can be installed."
                case .failure(let error): self.status = error.localizedDescription
                }
            }
        }
    }
    func install() {
        guard let serial = selectedSerial, let apk = Bundle.main.url(forResource: "PhoneXR", withExtension: "apk") else {
            status = "The bundled NextVR installer was not found."; return
        }
        busy = true; status = "Installing NextVR…"; let bridge = bridge
        Task.detached {
            let result = Result { try bridge.install(apk: apk, serial: serial) }
            await MainActor.run { self.busy = false; self.status = result.fold({ $0 }, { $0.localizedDescription }) }
        }
    }
}

private extension Result {
    func fold<T>(_ success: (Success) -> T, _ failure: (Failure) -> T) -> T {
        switch self { case .success(let value): return success(value); case .failure(let error): return failure(error) }
    }
}
