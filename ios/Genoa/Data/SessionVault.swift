import Foundation
import Security

enum SessionVault {
  private static var query: [String: Any] {
    [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "app.genoa.session", kSecAttrAccount as String: "current",
    ]
  }
  static func read() throws -> Credentials? {
    var q = query
    q[kSecReturnData as String] = true
    var item: CFTypeRef?
    let status = SecItemCopyMatching(q as CFDictionary, &item)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let data = item as? Data else {
      throw APIError(status: 0, message: "Unlock your iPhone to access your saved session.")
    }
    return try JSONDecoder().decode(Credentials.self, from: data)
  }
  static func save(_ credentials: Credentials) throws {
    let data = try JSONEncoder().encode(credentials)
    let attributes: [String: Any] = [
      kSecValueData as String: data,
      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    if status == errSecItemNotFound {
      status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil)
    }
    guard status == errSecSuccess else {
      throw APIError(status: 0, message: "Couldn’t securely save your session.")
    }
  }
  static func clear() throws {
    let status = SecItemDelete(query as CFDictionary)
    guard status == errSecSuccess || status == errSecItemNotFound else {
      throw APIError(status: 0, message: "Couldn’t remove your saved session. Try again.")
    }
  }
}
