import ActivityKit
import Security
import Foundation
import Observation

@MainActor
enum LiveNotificationRegistration {
    enum Failure: Error {
        case missingEndpoint, notificationsDenied, deviceNotProvisioned, server
    }

    static var endpoint: URL? {
        guard let host = Bundle.main.object(forInfoDictionaryKey: "NigeyoLiveRegistrationHost") as? String,
              !host.isEmpty, host.utf8.allSatisfy({
                  (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 46
              }) else { return nil }
        var url = URLComponents()
        url.scheme = "https"
        url.host = host
        url.path = "/v2/subscription"
        return url.url
    }

    private struct Subscription: Encodable, Equatable {
        var version: Int
        var enabled: Bool
        var targets: [RegionalTarget]
        var activity: ActivityRegistration?
        var deviceToken: String
        var language: String
    }

    private struct ActivityRegistration: Encodable, Equatable {
        var token: String
        var language: String
    }

    static let status = SyncStatus()

    @Observable
    final class SyncStatus {
        var isSynced = false
    }

    private static var lastUpload: Subscription?
    private static var requests: Task<Void, Error>?
    private static var userUpdates = 0

    static func update(_ subscription: RegionalSubscription, language: LanguageID) async throws {
        userUpdates += 1
        defer { userUpdates -= 1 }
        status.isSynced = false
        if !subscription.enabled {
            try await removeInstallation()
            return
        }
        try await enqueue(subscription, language: language)
    }

    private static func serialize(_ work: @escaping @MainActor () async throws -> Void) async throws {
        let previous = requests
        let task = Task {
            _ = await previous?.result
            try await work()
        }
        requests = task
        try await task.value
    }

    private static func enqueue(_ subscription: RegionalSubscription, language: LanguageID) async throws {
        try await serialize {
            guard let endpoint else { throw Failure.missingEndpoint }
            let token = try await APNsRegistration.shared.tokenForLiveRegistration()
            let data = ActivityAuthorizationInfo().areActivitiesEnabled
                ? Activity<NigeyoActivityAttributes>.pushToStartToken : nil
            let activity = data.map { ActivityRegistration(token: hex($0), language: language.rawValue) }
            let body = Subscription(version: subscription.version, enabled: subscription.enabled,
                                    targets: subscription.targets, activity: activity, deviceToken: token,
                                    language: language.rawValue)
            guard body != lastUpload else {
                status.isSynced = true
                return
            }
            let credential = try await installationCredential(at: endpoint)
            do {
                try await put(body, at: endpoint, credential: credential)
            } catch Failure.deviceNotProvisioned {
                let recovered = try await installationCredential(at: endpoint, renew: true)
                try await put(body, at: endpoint, credential: recovered)
            }
            lastUpload = body
            status.isSynced = true
        }
    }

    static func refreshActivity(settings: SettingsStore) async {
        guard userUpdates == 0, settings.liveRegistrationNeedsRevocation, settings.regionalSubscription.enabled else { return }
        do { try await enqueue(settings.regionalSubscription, language: settings.language) }
        catch { status.isSynced = false }
    }

    private static func removeInstallation() async throws {
        try await serialize {
            guard let endpoint else { throw Failure.missingEndpoint }
            guard try readKeychain("bootstrap", at: endpoint) != nil else { return }
            let credential = try await installationCredential(at: endpoint)
            _ = try await request(at: installationURL(endpoint), method: "DELETE", credential: credential,
                                  expected: 204)
            try removeKeychain("credential", at: endpoint)
            try removeKeychain("bootstrap", at: endpoint)
            lastUpload = nil
            status.isSynced = false
        }
    }

    private static func installationURL(_ endpoint: URL) -> URL {
        endpoint.deletingLastPathComponent().appendingPathComponent("installations")
    }

    private struct EnrollmentResponse: Decodable {
        var credential: String
    }

    private static func installationCredential(at endpoint: URL, renew: Bool = false) async throws -> String {
        if !renew, let existing = try readKeychain("credential", at: endpoint) { return existing }
        let bootstrap: String
        if let existing = try readKeychain("bootstrap", at: endpoint) {
            bootstrap = existing
        } else {
            var bytes = [UInt8](repeating: 0, count: 32)
            let result = bytes.withUnsafeMutableBytes {
                SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
            }
            guard result == errSecSuccess else { throw Failure.server }
            bootstrap = hex(Data(bytes))
            try writeKeychain(bootstrap, account: "bootstrap", at: endpoint)
        }
        let data = try await request(at: installationURL(endpoint), method: "PUT", credential: bootstrap, expected: 200)
        let credential = try JSONDecoder.nigeyo.decode(EnrollmentResponse.self, from: data).credential
        guard credential.range(of: #"^[a-f0-9]{64}\.[a-f0-9]{64}$"#, options: .regularExpression) != nil else {
            throw Failure.server
        }
        try writeKeychain(credential, account: "credential", at: endpoint)
        return credential
    }

    private static func keychainQuery(_ account: String, at endpoint: URL) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "jp.nigeyo.live-registration",
            kSecAttrAccount as String: "\(endpoint.host ?? "")/\(account)",
        ]
    }

    private static func readKeychain(_ account: String, at endpoint: URL) throws -> String? {
        var query = keychainQuery(account, at: endpoint)
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let code = SecItemCopyMatching(query as CFDictionary, &result)
        if code == errSecItemNotFound { return nil }
        guard code == errSecSuccess, let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else { throw Failure.server }
        return value
    }

    private static func writeKeychain(_ value: String, account: String, at endpoint: URL) throws {
        let query = keychainQuery(account, at: endpoint)
        let data = Data(value.utf8)
        let code = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if code == errSecSuccess { return }
        guard code == errSecItemNotFound else { throw Failure.server }
        var item = query
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        item[kSecValueData as String] = data
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw Failure.server }
    }

    private static func removeKeychain(_ account: String, at endpoint: URL) throws {
        let code = SecItemDelete(keychainQuery(account, at: endpoint) as CFDictionary)
        guard code == errSecSuccess || code == errSecItemNotFound else { throw Failure.server }
    }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private struct UpdateToken: Encodable {
        var token: String
        var sessionId: UUID
        var hazard: Hazard
        var sourceEventId: String
        var issuedAt: Date
    }

    static func uploadUpdateToken(_ token: Data, command: RegionalAlert, settings: SettingsStore) async {
        guard settings.regionalSubscription.enabled,
              let endpoint = endpoint?.deletingLastPathComponent().appendingPathComponent("activity") else { return }
        let body = UpdateToken(token: hex(token), sessionId: command.commandId, hazard: command.hazard,
                               sourceEventId: command.sourceEventId, issuedAt: command.issuedAt)
        try? await serialize {
            guard let credential = try readKeychain("credential", at: endpoint) else { return }
            try await put(body, at: endpoint, credential: credential)
        }
    }

    private static func put(_ body: some Encodable, at endpoint: URL, credential: String) async throws {
        let data = try JSONEncoder.nigeyo.encode(body)
        _ = try await request(at: endpoint, method: "PUT", credential: credential, expected: 204, body: data)
    }

    private static func request(at endpoint: URL, method: String, credential: String,
                                expected: Int, body: Data? = nil) async throws -> Data {
        var request = URLRequest(url: endpoint, timeoutInterval: 12)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization")
        request.httpBody = body
        let (data, response) = try await URLSession.shared.data(for: request)
        switch (response as? HTTPURLResponse)?.statusCode {
        case expected: return data
        case 403: throw Failure.deviceNotProvisioned
        default: throw Failure.server
        }
    }

    static func messageKey(_ error: Error) -> String {
        switch error {
        case Failure.missingEndpoint: "regions.registration.unavailable"
        case Failure.notificationsDenied: "practice.notificationsDenied"
        case Failure.deviceNotProvisioned: "regions.registration.device"
        default: "regions.registration.failed"
        }
    }
}
