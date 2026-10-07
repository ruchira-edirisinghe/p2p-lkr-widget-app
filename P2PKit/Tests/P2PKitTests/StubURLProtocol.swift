import Foundation

/// Serves canned responses so the suite never touches the network.
final class StubURLProtocol: URLProtocol {
    struct Stub {
        var status: Int = 200
        var body: Data = Data()
        var error: Error?
    }

    nonisolated(unsafe) static var stub = Stub()
    nonisolated(unsafe) static var lastRequestBody: Data?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        // URLSession strips httpBody from the request handed to a protocol,
        // so read it back from the body stream.
        Self.lastRequestBody = request.httpBody ?? request.httpBodyStream.flatMap { stream in
            stream.open()
            var data = Data()
            let size = 4096
            let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: size)
            defer { buffer.deallocate(); stream.close() }
            while stream.hasBytesAvailable {
                let read = stream.read(buffer, maxLength: size)
                if read <= 0 { break }
                data.append(buffer, count: read)
            }
            return data
        }

        if let error = Self.stub.error {
            client?.urlProtocol(self, didFailWithError: error)
            return
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: Self.stub.status,
                                       httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.stub.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    /// Sets the stub and clears any previously captured request. Callers must
    /// be serialized: this is process-global mutable state, and Swift Testing
    /// runs tests in parallel unless a suite opts out with `.serialized`.
    static func install(status: Int = 200, body: Data = Data(), error: Error? = nil) {
        stub = Stub(status: status, body: body, error: error)
        lastRequestBody = nil
    }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }
}
