import Foundation

/// The **only** place that knows Binance's wire format. A schema change upstream
/// is a one-file fix by design.
public enum WireFormat {
    static let successCode = "000000"

    private struct Response: Decodable {
        let code: String
        let message: String?
        let data: [Row]?
    }

    private struct Row: Decodable {
        let adv: Adv
        let advertiser: Advertiser
    }

    /// Numeric fields arrive as strings; rates and counts arrive as numbers.
    private struct Adv: Decodable {
        let price: String?
        let tradableQuantity: String?
        let minSingleTransAmount: String?
        let dynamicMaxSingleTransAmount: String?
        let maxSingleTransAmount: String?
        let payTimeLimit: Int?
    }

    private struct Advertiser: Decodable {
        let nickName: String?
        let monthOrderCount: Int?
        let monthFinishRate: Double?
        let positiveRate: Double?
    }

    public static func decodeAds(from data: Data) throws -> [Ad] {
        let response: Response
        do {
            response = try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw P2PError.malformed(String(describing: error))
        }

        guard response.code == successCode else {
            throw P2PError.apiCode(response.code, response.message)
        }

        let rows = response.data ?? []
        guard !rows.isEmpty else { throw P2PError.emptyResult }

        // A single unparseable ad is skipped, not fatal: the rest of the book
        // is still a usable observation.
        let ads = rows.compactMap(normalise)
        guard !ads.isEmpty else { throw P2PError.emptyResult }
        return ads
    }

    private static func normalise(_ row: Row) -> Ad? {
        guard let price = row.adv.price.flatMap({ Double($0) }), price > 0,
              let stock = row.adv.tradableQuantity.flatMap({ Double($0) }),
              let minFiat = row.adv.minSingleTransAmount.flatMap({ Double($0) })
        else { return nil }

        // dynamicMaxSingleTransAmount reflects live stock; fall back to the
        // static ceiling if it is ever absent.
        let maxRaw = row.adv.dynamicMaxSingleTransAmount ?? row.adv.maxSingleTransAmount
        guard let maxFiat = maxRaw.flatMap({ Double($0) }) else { return nil }

        return Ad(
            price: price,
            availableUSDT: stock,
            minFiat: minFiat,
            maxFiat: maxFiat,
            payTimeLimitMinutes: row.adv.payTimeLimit ?? 0,
            advertiserName: row.advertiser.nickName ?? "Unknown",
            monthOrderCount: row.advertiser.monthOrderCount ?? 0,
            monthFinishRate: row.advertiser.monthFinishRate ?? 0,
            positiveRate: row.advertiser.positiveRate ?? 0
        )
    }
}
