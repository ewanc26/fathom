import XCTest
import FathomShared

/// These cover the boundary the app actually crosses: the facade's decoding
/// and formatting. The arithmetic itself is tested in the shared module, so a
/// duplicate here would only prove the same thing twice.
final class FathomCoreTests: XCTestCase {

    func testDecodesAPlayPageAcrossTheBridge() {
        let page = FathomCore.shared.playsPage(json: Self.playPage)

        XCTAssertTrue(page.parsed)
        XCTAssertEqual(page.plays.count, 1)
        XCTAssertEqual(page.skipped, 0)
        XCTAssertEqual(page.cursor, "3mzq")

        let play = page.plays[0]
        XCTAssertEqual(play.uri, "at://did:plc:x/fm.teal.feed.play/3mzq")
        XCTAssertEqual(play.cid, "bafy")
        XCTAssertEqual(play.title(), "aneurysm")
        XCTAssertEqual(play.artistLine(), "SXCREDMANE & ZMAJOR")
        XCTAssertEqual(play.durationText(), "1:50")
        XCTAssertEqual(play.serviceLabel(), "Apple Music")
    }

    func testDeduplicatesPlaysByUriAcrossTheBridge() {
        let plays = FathomCore.shared.playsPage(json: Self.playPage).plays

        XCTAssertEqual(FathomCore.shared.distinctPlays(plays: plays + plays).count, 1)
    }

    func testSearchIndexCrossesTheBridge() {
        let plays = FathomCore.shared.playsPage(json: Self.playPage).plays
        let index = FathomCore.shared.searchIndex(plays: plays)

        XCTAssertEqual(index.search(query: "aneurysm").count, 1)
        XCTAssertEqual(index.search(query: "SXCREDMANE").count, 1)
        XCTAssertEqual(index.search(query: "nothing").count, 0)
        XCTAssertEqual(FathomCore.shared.foldedForSearch(text: "Beyoncé"), "beyonce")
    }

    func testMonthLabelsCrossTheBridge() {
        let day = FathomCore.shared.epochDay(epochSeconds: 1_794_070_932, tzOffsetMinutes: 0)

        XCTAssertEqual(FathomCore.shared.monthKey(epochDay: day), "2026-11")
        XCTAssertEqual(FathomCore.shared.monthLabel(epochDay: day), "Nov 2026")
    }

    func testIncrementalMergeCrossesTheBridge() {
        let plays = FathomCore.shared.playsPage(json: Self.playPage).plays

        XCTAssertTrue(FathomCore.shared.canResume(plays: plays, complete: true))
        XCTAssertFalse(FathomCore.shared.canResume(plays: plays, complete: false))

        let merger = PlayMerger(known: plays)
        let merge = merger.addPage(page: plays)
        XCTAssertTrue(merge.pageWasFullyKnown)
        XCTAssertEqual(merge.newRecords, 0)
        XCTAssertEqual(merger.plays.count, 1)
    }

    func testKeepsGoodRecordsWhenASiblingIsMalformed() {
        let page = FathomCore.shared.playsPage(json: Self.pageWithBrokenSibling)
        XCTAssertEqual(page.plays.count, 1)
        XCTAssertEqual(page.skipped, 1)
    }

    func testReportsAnUnrecognisedBody() {
        XCTAssertFalse(FathomCore.shared.playsPage(json: #"{"error":"InvalidRequest"}"#).parsed)
    }

    func testFormatsDurationsAsAmounts() {
        XCTAssertEqual(FathomCore.shared.listeningText(seconds: 11_520), "3 h 12 m")
        XCTAssertEqual(FathomCore.shared.listeningText(seconds: 42), "42 s")
    }

    func testPeriodIdsMatchTheLexiconVocabulary() {
        XCTAssertEqual(FathomCore.shared.periodIds(), ["7days", "30days", "all"])
        XCTAssertEqual(FathomCore.shared.periodLabel(id: "all"), "All time")
    }

    func testRetryPolicyCrossesTheBridge() {
        let now: Int64 = 1_789_862_400
        XCTAssertEqual(FathomCore.shared.retryMaxAttempts(), Int32(4))
        XCTAssertTrue(FathomCore.shared.isRetryableStatus(status: 429))
        XCTAssertFalse(FathomCore.shared.isRetryableStatus(status: 404))
        XCTAssertEqual(
            FathomCore.shared.retryDelayMillis(
                attempt: 0,
                retryAfterHeader: "5",
                nowEpochSeconds: now
            ),
            Int64(5_000)
        )
        XCTAssertEqual(
            FathomCore.shared.retryDelayMillis(
                attempt: 0,
                retryAfterHeader: "Sun, 20 Sep 2026 00:00:05 GMT",
                nowEpochSeconds: now
            ),
            Int64(5_000)
        )
    }

    func testComputesStatsAcrossTheBridge() {
        let plays = FathomCore.shared.playsPage(json: Self.playPage).plays
        let stats = FathomCore.shared.computeStats(
            plays: plays,
            nowEpochSeconds: 1_794_070_932,
            tzOffsetMinutes: 0,
            periodId: "30days",
            sampled: false,
            chartDays: 14
        )

        XCTAssertEqual(stats.playCount, 1)
        XCTAssertEqual(stats.recentDays.count, 14)
        XCTAssertEqual(stats.recentDays.last?.dayKey, "2026-11-07")
        XCTAssertEqual(stats.recentPlayCount, 1)
        XCTAssertEqual(stats.topTracks.count, 1)
        XCTAssertEqual(stats.topTracks.first?.name, "aneurysm")
        XCTAssertEqual(stats.topTracks.first?.artist, "SXCREDMANE & ZMAJOR")
    }

    private static let playPage = """
        {"records":[{"uri":"at://did:plc:x/fm.teal.feed.play/3mzq","cid":"bafy",
          "value":{"$type":"fm.teal.feed.play",
                   "artists":[{"artistName":"SXCREDMANE & ZMAJOR"}],
                   "duration":110,"trackName":"aneurysm",
                   "playedTime":"2026-11-07T17:02:12Z",
                   "releaseName":"aneurysm - Single",
                   "musicServiceUri":"https://music.apple.com"}}],
         "cursor":"3mzq"}
        """

    private static let pageWithBrokenSibling = """
        {"records":[
          {"uri":"at://did:plc:x/fm.teal.feed.play/3mzq","value":{"trackName":"aneurysm"}},
          {"uri":"at://did:plc:x/fm.teal.feed.play/bad","value":{"trackName":5}}
        ]}
        """
}
