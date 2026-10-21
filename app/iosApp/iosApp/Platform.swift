import AuthenticationServices
import Foundation
import Security
import Ui
import UIKit

/// Passkeys (AuthenticationServices, RP ID switchboard.applabs.llc — needs the app's
/// `webcredentials:switchboard.applabs.llc` entitlement and the domain's apple-app-site-association) and
/// the Keychain, for the shared Kotlin code (IosPlatform in MainViewController.kt).
/// Written without a Mac to build it on: check on the first iOS build.
final class Platform: NSObject, IosPlatform, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private var pending: ((String?, String?) -> Void)?
    private static let rpId = "switchboard.applabs.llc"
    private static let service = "llc.applabs.switchboard"

    var deviceName: String { UIDevice.current.name }

    // MARK: passkeys

    func createPasskey(optionsJson: String, done: @escaping (String?, String?) -> Void) {
        guard let o = Platform.object(optionsJson),
              let challenge = Platform.b64url((o["challenge"] as? String) ?? ""),
              let user = o["user"] as? [String: Any],
              let userId = Platform.b64url((user["id"] as? String) ?? "") else { done(nil, "Invalid passkey options"); return }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: Platform.rpId)
        let request = provider.createCredentialRegistrationRequest(challenge: challenge, name: (user["name"] as? String) ?? "Switchboard", userID: userId)
        request.userVerificationPreference = .required
        run(request, done)
    }

    func getPasskey(optionsJson: String, done: @escaping (String?, String?) -> Void) {
        guard let o = Platform.object(optionsJson), let challenge = Platform.b64url((o["challenge"] as? String) ?? "") else {
            done(nil, "Invalid passkey options"); return
        }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: Platform.rpId)
        let request = provider.createCredentialAssertionRequest(challenge: challenge)
        request.userVerificationPreference = .required
        run(request, done)
    }

    private func run(_ request: ASAuthorizationRequest, _ done: @escaping (String?, String?) -> Void) {
        pending = done
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        var json: [String: Any]?
        if let r = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialRegistration, let att = r.rawAttestationObject {
            let id = Platform.b64url(r.credentialID)
            json = ["type": "public-key", "id": id, "rawId": id, "clientExtensionResults": [String: Any](),
                    "response": ["clientDataJSON": Platform.b64url(r.rawClientDataJSON), "attestationObject": Platform.b64url(att)]]
        } else if let a = authorization.credential as? ASAuthorizationPlatformPublicKeyCredentialAssertion {
            let id = Platform.b64url(a.credentialID)
            json = ["type": "public-key", "id": id, "rawId": id, "clientExtensionResults": [String: Any](),
                    "response": ["clientDataJSON": Platform.b64url(a.rawClientDataJSON), "authenticatorData": Platform.b64url(a.rawAuthenticatorData),
                                 "signature": Platform.b64url(a.signature), "userHandle": Platform.b64url(a.userID)]]
        }
        let text = json.flatMap { try? JSONSerialization.data(withJSONObject: $0) }.flatMap { String(data: $0, encoding: .utf8) }
        finish(text, text == nil ? "Unexpected passkey response" : nil)
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        finish(nil, cancelled ? "cancelled" : error.localizedDescription)
    }

    private func finish(_ json: String?, _ error: String?) { let p = pending; pending = nil; p?(json, error) }

    // MARK: keychain

    func keychainGet(key: String) -> String? {
        var q = base(key); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func keychainSet(key: String, value: String) {
        keychainRemove(key: key)
        var q = base(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    func keychainRemove(key: String) { SecItemDelete(base(key) as CFDictionary) }

    private func base(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: Platform.service, kSecAttrAccount as String: key]
    }

    // MARK: helpers

    private static func object(_ json: String) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]
    }
    private static func b64url(_ s: String) -> Data? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }
    private static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}
