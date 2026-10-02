import Foundation
import LocalAuthentication

/// アプリ起動時と一定時間経過後の再認証（企画書 15）。
///
/// 端末に画面ロックが設定されていない場合は業務利用を許可しない。
enum AppLock {

    /// 再認証までの猶予。画面を離れてこの時間を超えたら、扱っていた項目を破棄して再認証する。
    static let reauthAfterSeconds: TimeInterval = 180

    enum Availability { case available, noScreenLock, unavailable }

    static func availability() -> Availability {
        let context = LAContext()
        var error: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) { return .available }
        if let code = error?.code,
           code == LAError.passcodeNotSet.rawValue || code == LAError.biometryNotEnrolled.rawValue {
            return .noScreenLock
        }
        return .unavailable
    }

    static func authenticate(reason: String, completion: @escaping (Bool, String?) -> Void) {
        let context = LAContext()
        context.localizedCancelTitle = "キャンセル"
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, error in
            DispatchQueue.main.async {
                if success {
                    completion(true, nil)
                } else {
                    let code = (error as? LAError)?.code.rawValue ?? -1
                    completion(false, "E_AUTH_\(code)")
                }
            }
        }
    }
}
