package uk.ewancroft.fathom

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.ewancroft.fathom.data.Dashboard
import uk.ewancroft.fathom.data.FathomException
import uk.ewancroft.fathom.data.FathomRepository
import uk.ewancroft.fathom.data.HistoryCacheStore
import uk.ewancroft.fathom.data.IdentityStore
import uk.ewancroft.fathom.shared.cache.CachedHistory
import uk.ewancroft.fathom.shared.cache.HistoryCache
import uk.ewancroft.fathom.shared.model.Play
import uk.ewancroft.fathom.shared.model.ReadCheckpoint
import uk.ewancroft.fathom.shared.stats.Period
import uk.ewancroft.fathom.shared.stats.StatsCalculator

/**
 * The state holder is where cache restore, cancellation and the reading
 * banner meet. These are JVM tests with a fake repository and a fixed
 * dispatcher, so they need no emulator and no PDS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FathomViewModelTest {

    private lateinit var application: Application
    private lateinit var store: IdentityStore
    private lateinit var cache: HistoryCacheStore

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        store = IdentityStore(application)
        cache = HistoryCacheStore(application)
        cache.clear()
        store.clearIdentity()
    }

    @After
    fun tearDown() {
        cache.clear()
        store.clearIdentity()
        Dispatchers.resetMain()
    }

    @Test
    fun aLargerCachedSnapshotSurvivesTheFirstEmptyPage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        store.saveIdentity(IDENTITY)
        cache.write(cachedHistory(count = 2))
        val repository = FakeRepository { _, onProgress ->
            onProgress?.invoke(testDashboard(emptyList(), sampled = true))
            awaitCancellation()
        }

        val viewModel = FathomViewModel(application, repository, store, cache, dispatcher)
        try {
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(2, state.dashboard?.plays?.size)
            assertEquals(true, state.dashboard?.sampled)
            assertTrue(state.loading)
            assertTrue(state.readingHistory)
        } finally {
            viewModel.disconnect()
        }
    }

    @Test
    fun aSupersededLoadDoesNotLeaveAnError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = FakeRepository { identity, _ ->
            if (identity == "first") {
                delay(1_000)
                throw FathomException("the first read failed")
            }
            testDashboard(emptyList())
        }

        val viewModel = FathomViewModel(application, repository, store, cache, dispatcher)
        try {
            viewModel.connect("first")
            runCurrent()
            viewModel.connect("second")
            advanceUntilIdle()

            assertEquals("second", viewModel.state.value.identity)
            assertNull(viewModel.state.value.error)
            assertFalse(viewModel.state.value.loading)
        } finally {
            viewModel.disconnect()
        }
    }

    @Test
    fun dismissingTheReadingBannerDoesNotStopTheRead() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val play = testPlay("one", "one")
        val repository = FakeRepository { _, onProgress ->
            onProgress?.invoke(testDashboard(listOf(play), sampled = true))
            gate.await()
            testDashboard(listOf(play))
        }

        val viewModel = FathomViewModel(application, repository, store, cache, dispatcher)
        try {
            viewModel.connect("alice")
            runCurrent()
            assertTrue(viewModel.state.value.readingHistory)

            viewModel.dismissReadingStatus()
            assertTrue(viewModel.state.value.readingStatusDismissed)

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.readingHistory)
            assertEquals(1, viewModel.state.value.dashboard?.plays?.size)
        } finally {
            viewModel.disconnect()
        }
    }

    @Test
    fun disconnectingClearsTheSavedCopyAndState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        store.saveIdentity(IDENTITY)
        cache.write(cachedHistory(count = 1))
        val repository = FakeRepository { _, onProgress ->
            onProgress?.invoke(testDashboard(emptyList(), sampled = true))
            awaitCancellation()
        }

        val viewModel = FathomViewModel(application, repository, store, cache, dispatcher)
        try {
            advanceUntilIdle()
            viewModel.disconnect()

            assertNull(viewModel.state.value.identity)
            assertNull(viewModel.state.value.dashboard)
            assertNull(cache.read(IDENTITY))
            assertNull(store.identity())
        } finally {
            viewModel.disconnect()
        }
    }

    private class FakeRepository(
        private val loader: suspend (String, ((Dashboard) -> Unit)?) -> Dashboard,
    ) : FathomRepository() {

        override suspend fun load(
            identity: String,
            period: Period,
            checkpoint: ReadCheckpoint?,
            forceFull: Boolean,
            nowEpochSeconds: Long,
            tzOffsetMinutes: Int,
            onProgress: ((Dashboard) -> Unit)?,
        ): Dashboard = loader(identity, onProgress)

        override fun fromCache(
            cached: CachedHistory,
            period: Period,
            nowEpochSeconds: Long,
            tzOffsetMinutes: Int,
        ): Dashboard = testDashboard(cached.plays, cached.sampled)

        override fun toCache(
            identity: String,
            dashboard: Dashboard,
            savedAtEpochSeconds: Long,
        ): CachedHistory = CachedHistory(
            version = HistoryCache.VERSION,
            identity = identity,
            did = dashboard.did,
            handle = dashboard.handle,
            profile = dashboard.profile,
            status = dashboard.status,
            plays = dashboard.plays,
            skipped = dashboard.skippedRecords,
            legacy = dashboard.usedLegacyNamespace,
            sampled = dashboard.sampled,
            savedAtEpochSeconds = savedAtEpochSeconds,
        )
    }

    private companion object {
        const val IDENTITY = "alice.example"
        const val DID = "did:plc:test"
        const val NOW = 1_794_070_932L

        fun testDashboard(plays: List<Play>, sampled: Boolean = false): Dashboard = Dashboard(
            did = DID,
            handle = IDENTITY,
            profile = null,
            status = null,
            plays = plays,
            stats = StatsCalculator.compute(
                plays = plays,
                nowEpochSeconds = NOW,
                tzOffsetMinutes = 0,
                period = Period.ThirtyDays,
                sampled = sampled,
            ),
            sampled = sampled,
            skippedRecords = 0,
            usedLegacyNamespace = false,
        )

        fun testPlay(track: String, suffix: String): Play = Play(
            trackName = track,
            playedTime = "2026-11-07T09:00:00Z",
            uri = "at://did:plc:test/fm.teal.feed.play/$suffix",
        )

        fun cachedHistory(count: Int, sampled: Boolean = true): CachedHistory = CachedHistory(
            version = HistoryCache.VERSION,
            identity = IDENTITY,
            did = DID,
            plays = (1..count).map { testPlay("Track $it", "uri$it") },
            sampled = sampled,
        )
    }
}
