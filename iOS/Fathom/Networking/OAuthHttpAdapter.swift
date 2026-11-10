import Foundation
import FathomShared

/// The iOS edge of the shared OAuth engine: URLSession behind the one
/// synchronous `OAuthHttp` seam. The engine decides everything OAuth-shaped;
/// this type only performs the call it is handed.
///
/// Calls block their calling thread (the read transport's
/// `TealClient.shared` follows the same rule from a background queue), which
/// is the price of keeping the engine synchronous and total — a reply with
/// status 0 for any failure rather than an exception across the boundary.
final class OAuthHttpAdapter: OAuthHttp {

    init() {}

    func call(call: OAuthHttpCall) -> OAuthHttpReply {
        guard let url = URL(string: call.url) else {
            return OAuthHttpReply(status: 0, headers: [:], body: nil)
        }
        var request = URLRequest(url: url)
        request.httpMethod = call.method
        request.timeoutInterval = 45
        for (name, value) in call.headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        if let body = call.rawBody {
            request.httpBody = Data(body.utf8)
        } else if let form = call.form {
            let encoded = form
                .map { (name, value) in encode(name) + "=" + encode(value) }
                .joined(separator: "&")
            request.httpBody = Data(encoded.utf8)
        }

        let semaphore = DispatchSemaphore(value: 0)
        let box = ReplyBox()
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            defer { semaphore.signal() }
            guard error == nil, let http = response as? HTTPURLResponse else { return }
            var headers: [String: String] = [:]
            for (name, value) in http.allHeaderFields {
                if let name = name as? String, let value = value as? String {
                    headers[name.lowercased()] = value
                }
            }
            box.status = http.statusCode
            box.headers = headers
            box.body = data.flatMap { String(data: $0, encoding: .utf8) }
        }
        task.resume()
        semaphore.wait()

        return OAuthHttpReply(
            status: Int32(box.status),
            headers: box.headers,
            body: box.body
        )
    }

    private func encode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
    }
}

/// A reference box so the completion handler can deliver its reply to the
/// waiting caller without fighting the closure's capture rules.
private final class ReplyBox: @unchecked Sendable {
    var status: Int = 0
    var headers: [String: String] = [:]
    var body: String? = nil
}
