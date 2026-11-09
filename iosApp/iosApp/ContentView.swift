import SwiftUI
import ComposeApp

struct ContentView: View {
    var body: some View {
        ComposeView()
            // every inset is Compose's to apply — App puts safeDrawingPadding() on the content and
            // leaves the background running edge to edge. SwiftUI insets a representable by the
            // safe area as well unless told not to, and the two together were a status bar and a
            // home indicator of padding twice over, at the top and the bottom of every screen.
            // the keyboard is in .all too, and Compose handles that one through the same modifier
            .ignoresSafeArea()
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
