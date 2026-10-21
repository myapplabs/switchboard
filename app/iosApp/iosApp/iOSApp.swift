import SwiftUI

@main
struct iOSApp: App {
    private let platform = Platform()
    var body: some Scene {
        WindowGroup {
            ContentView(platform: platform)
        }
    }
}
