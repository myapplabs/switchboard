import SwiftUI
import Ui

struct ComposeView: UIViewControllerRepresentable {
    let platform: Platform
    func makeUIViewController(context: Context) -> UIViewController {
        #if DEBUG
        let dev = true
        #else
        let dev = false
        #endif
        return MainViewControllerKt.MainViewController(platform: platform, devSignIn: dev)
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    let platform: Platform
    var body: some View {
        ComposeView(platform: platform)
            .ignoresSafeArea()
            .onOpenURL { url in MainViewControllerKt.openInviteLink(url: url.absoluteString) }
    }
}
