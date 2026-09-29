import AppKit
import SwiftUI

struct SettingsView: View {
    @ObservedObject var model: SyncModel
    @State private var server = ""
    @State private var userID = ""
    @State private var showInvalidSettings = false

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack(spacing: 16) {
                appIcon
                    .frame(width: 68, height: 68)
                VStack(alignment: .leading, spacing: 8) {
                    Text("剪贴板同步")
                        .font(.title2.weight(.semibold))
                    HStack(spacing: 7) {
                        Circle()
                            .fill(model.status == "已连接"
                                  ? Color(red: 0.13, green: 0.64, blue: 0.49) : .orange)
                            .frame(width: 7, height: 7)
                        Text(model.status)
                            .foregroundStyle(.secondary)
                    }
                    .font(.subheadline)
                }
                Spacer()
            }

            Divider()

            VStack(alignment: .leading, spacing: 8) {
                Text("服务器地址")
                    .font(.subheadline.weight(.medium))
                TextField("https://sync.example.com", text: $server)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("同步 UUID")
                    .font(.subheadline.weight(.medium))
                HStack(spacing: 8) {
                    TextField("UUID", text: $userID)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                    Button {
                        model.copyID(userID)
                    } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .help("复制 UUID")
                    Button {
                        userID = UUID().uuidString
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .help("重新生成 UUID")
                }
            }

            HStack {
                Spacer()
                Button("保存设置") {
                    if model.save(server: server, userID: userID) {
                        server = model.settings.serverURL.absoluteString
                        userID = model.settings.userID.uuidString
                    } else {
                        showInvalidSettings = true
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(red: 0.25, green: 0.44, blue: 0.86))
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(minWidth: 440, idealWidth: 540, maxWidth: 700)
        .onAppear {
            server = model.settings.serverURL.absoluteString
            userID = model.settings.userID.uuidString
        }
        .alert("设置无效", isPresented: $showInvalidSettings) {
            Button("好", role: .cancel) {}
        } message: {
            Text("请输入有效的 HTTP(S) 服务器地址和 UUID。")
        }
    }

    @ViewBuilder
    private var appIcon: some View {
        if let path = Bundle.main.path(forResource: "AppIcon", ofType: "icns"),
           let image = NSImage(contentsOfFile: path) {
            Image(nsImage: image)
                .resizable()
                .interpolation(.high)
        } else {
            Image(systemName: "doc.on.clipboard")
                .resizable()
                .scaledToFit()
                .padding(14)
                .foregroundStyle(Color(red: 0.25, green: 0.44, blue: 0.86))
        }
    }
}
