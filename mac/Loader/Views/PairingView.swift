import SwiftUI
import CoreImage.CIFilterBuiltins

/// The pairing code (PRD §6 "First run"). Shown automatically until the phone
/// connects for the first time, and on demand from the toolbar afterwards.
struct PairingView: View {
    @EnvironmentObject var model: LoaderModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("Connect your phone").font(.title2).fontWeight(.semibold)
            if let code = model.pairingCodeText, let image = QRCode.image(for: code, size: 320) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 320, height: 320)
                    .background(Color.white)
                    .cornerRadius(8)
                VStack(alignment: .leading, spacing: 6) {
                    Label("Open the music tool on your phone", systemImage: "1.circle")
                    Label("Choose Sync, then Scan Code on Mac", systemImage: "2.circle")
                    Label("Point the camera at this code", systemImage: "3.circle")
                }
                .fixedSize(horizontal: false, vertical: true)
                Text("Both devices need to be on the same Wi-Fi.")
                    .foregroundStyle(.secondary)
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                ProgressView().padding()
                Text(waitingReason).multilineTextAlignment(.center).frame(maxWidth: 360)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
        }
        .padding(24)
        .frame(width: 440)
    }

    private var waitingReason: String {
        switch model.server {
        case .failed(let message): return message
        case .starting: return "Getting ready…"
        case .serving: return model.address == nil
            ? "This Mac isn't on a network yet. Join the same Wi-Fi as your phone and the code will appear."
            : "Getting ready…"
        }
    }
}

enum QRCode {
    static func image(for text: String, size: CGFloat) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
