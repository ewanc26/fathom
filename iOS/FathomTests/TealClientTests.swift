import XCTest
@testable import Fathom

/// The iOS transport decides whether a status code means absence or a failed
/// page. These use a fake URLProtocol so the retry loop and the 400/404 split
/// run without a PDS.
final class TealClientTests: XCTestCase {
    private let pds = "https://pds.example"
    private let did = "did:plc:test"
    private let collection = "fm.teal.feed.play"

    override func tearDown() {
        StubURLProtocol.reset()
        super.tearDown()
    }

    func testListRecords400IsNotAbsence() async {
        StubURLProtocol.enqueue(
            status: 400,
            body: #"{"error":"InvalidRequest","message":"bad cursor"}"#
        )

        do {
            _ = try await makeClient().listRecords(
                pds: pds,
                repo: did,
                collection: collection,
                cursor: "bad",
                limit: 100
            )
            XCTFail("expected a rejected page")
        } catch let error as FathomError {
            XCTAssertFalse(error.absent)
            XCTAssertTrue(error.message.contains("bad cursor"))
        } catch {
            XCTFail("unexpected error: \(error)")
        }

        XCTAssertEqual(StubURLProtocol.requestCount, 1)
    }

    func testMissingListIsAbsenceAndIsNotRetried() async {
        StubURLProtocol.enqueue(status: 404, body: "{}")

        do {
            _ = try await makeClient().listRecords(
                pds: pds,
                repo: did,
                collection: collection,
                cursor: nil,
                limit: 100
            )
            XCTFail("expected absence")
        } catch let error as FathomError {
            XCTAssertTrue(error.absent)
        } catch {
            XCTFail("unexpected error: \(error)")
        }

        XCTAssertEqual(StubURLProtocol.requestCount, 1)
    }

    func testBadRequestFromGetRecordIsAbsence() async throws {
        StubURLProtocol.enqueue(status: 400, body: "{}")

        let body = try await makeClient().getRecord(
            pds: pds,
            repo: did,
            collection: "fm.teal.actor.status",
            rkey: "self"
        )

        XCTAssertNil(body)
        XCTAssertEqual(StubURLProtocol.requestCount, 1)
    }

    func testRetriesARateLimitAndThenSucceeds() async throws {
        StubURLProtocol.enqueue(status: 429, body: "{}", headers: ["Retry-After": "0"])
        StubURLProtocol.enqueue(status: 200, body: #"{"records":[],"cursor":null}"#)

        let body = try await makeClient().listRecords(
            pds: pds,
            repo: did,
            collection: collection,
            cursor: nil,
            limit: 100
        )

        XCTAssertTrue(body.contains("\"records\""))
        XCTAssertEqual(StubURLProtocol.requestCount, 2)
    }

    func testStopsAfterThreeRetries() async {
        for _ in 0..<4 {
            StubURLProtocol.enqueue(status: 500, body: "{}", headers: ["Retry-After": "0"])
        }

        do {
            _ = try await makeClient().listRecords(
                pds: pds,
                repo: did,
                collection: collection,
                cursor: nil,
                limit: 100
            )
            XCTFail("expected the retry cap")
        } catch let error as FathomError {
            XCTAssertTrue(error.message.contains("500"))
        } catch {
            XCTFail("unexpected error: \(error)")
        }

        XCTAssertEqual(StubURLProtocol.requestCount, 4)
    }

    private func makeClient() -> TealClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return TealClient(session: URLSession(configuration: configuration))
    }
}

private final class StubURLProtocol: URLProtocol {
    private struct Stub {
        let status: Int
        let body: String
        let headers: [String: String]
    }

    private static let lock = NSLock()
    private static var stubs: [Stub] = []
    private static var requests = 0

    static var requestCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }

    static func enqueue(
        status: Int,
        body: String,
        headers: [String: String] = [:]
    ) {
        lock.lock()
        stubs.append(Stub(status: status, body: body, headers: headers))
        lock.unlock()
    }

    static func reset() {
        lock.lock()
        stubs.removeAll()
        requests = 0
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        Self.requests += 1
        let stub = Self.stubs.isEmpty
            ? Stub(status: 500, body: "{}", headers: [:])
            : Self.stubs.removeFirst()
        Self.lock.unlock()

        guard let url = request.url,
              let response = HTTPURLResponse(
                url: url,
                statusCode: stub.status,
                httpVersion: "HTTP/1.1",
                headerFields: stub.headers
              ) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }

        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(stub.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
