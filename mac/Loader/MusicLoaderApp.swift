import SwiftUI

@main
struct MusicLoaderApp: App {
    @StateObject private var model = LoaderModel()

    var body: some Scene {
        WindowGroup("Music Loader") {
            ContentView()
                .environmentObject(model)
                .onAppear { model.start() }
        }
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
    }
}
