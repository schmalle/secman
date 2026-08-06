package com.secman.mcp.tools

import com.secman.domain.Asset
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression tests for the unbounded asset reads in get_assets and get_all_assets_detail.
 *
 * Both tools read the entire asset table with findAll() (or every name match with
 * Pageable.UNPAGED, which emits no LIMIT) and then sliced the result in Kotlin, so cost grew
 * with the table rather than the requested page. Same defect class as the vulnerability tools,
 * on a smaller table.
 */
class AssetToolsPagingTest {

    private val assetRepository = mockk<AssetRepository>()

    private val getAssets = GetAssetsTool(assetRepository)
    private val getAllAssetsDetail = GetAllAssetsDetailTool(assetRepository)

    private val asset = Asset(id = 1L, name = "web-01", type = "SERVER", ip = "10.0.0.1", owner = "alice")

    private fun ctx(accessibleIds: Set<Long>?) = mockk<McpExecutionContext>().also {
        every { it.getFilterableAssetIds() } returns accessibleIds
        every { it.canAccessAsset(any()) } returns true
    }

    private fun onePage(size: Int = 50) = Page.of(listOf(asset), Pageable.from(0, size), 1L)

    /** Trap: stub the unbounded reads so buggy code still "works" and the verify is what fails. */
    private fun stubUnboundedReads() {
        every { assetRepository.findAll() } returns listOf(asset)
        every {
            assetRepository.findByNameContainingIgnoreCase(any<String>(), Pageable.UNPAGED)
        } returns Page.of(listOf(asset), Pageable.UNPAGED, 1L)
        // The List-returning derived queries are unbounded by construction: they carry no
        // Pageable at all, so findByType("SERVER") is effectively findAll().
        every { assetRepository.findByType(any<String>()) } returns listOf(asset)
        every { assetRepository.findByOwner(any<String>()) } returns listOf(asset)
        every { assetRepository.findByIpContainingIgnoreCase(any<String>()) } returns listOf(asset)
        every { assetRepository.findByGroupsContaining(any<String>()) } returns listOf(asset)
        // get_all_assets_detail re-reads the page with workgroups fetch-joined, because reading
        // the lazy collection off a detached entity throws "no session".
        every { assetRepository.findAllWithWorkgroups(any()) } returns listOf(asset)
    }

    private fun assertNoUnboundedRead() {
        verify(exactly = 0) { assetRepository.findAll() }
        verify(exactly = 0) { assetRepository.findByNameContainingIgnoreCase(any<String>(), Pageable.UNPAGED) }
        verify(exactly = 0) { assetRepository.findByType(any<String>()) }
        verify(exactly = 0) { assetRepository.findByOwner(any<String>()) }
        verify(exactly = 0) { assetRepository.findByIpContainingIgnoreCase(any<String>()) }
        verify(exactly = 0) { assetRepository.findByGroupsContaining(any<String>()) }
    }

    // ------------------------------------------------------------------ get_assets

    @Test
    fun `get_assets unfiltered is paged in SQL for a restricted caller`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdIn(setOf(1L), any()) } returns onePage()

        val result = getAssets.execute(mapOf("pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
        verify { assetRepository.findByIdIn(setOf(1L), match { it.size == 50 }) }
    }

    @Test
    fun `get_assets name filter is paged in SQL for a restricted caller`() = runBlocking {
        stubUnboundedReads()
        every {
            assetRepository.findByIdInAndNameContainingIgnoreCase(setOf(1L), "web", any())
        } returns onePage()

        val result = getAssets.execute(mapOf("name" to "web", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
        verify {
            assetRepository.findByIdInAndNameContainingIgnoreCase(setOf(1L), "web", match { it.size == 50 })
        }
    }

    @Test
    fun `get_assets keeps admin on the paged repository calls`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findAll(any<Pageable>()) } returns onePage()

        val result = getAssets.execute(mapOf("pageSize" to 50), ctx(null))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
        verify { assetRepository.findAll(match<Pageable> { it.size == 50 }) }
    }

    // ------------------------------------------------------------------ get_all_assets_detail

    @Test
    fun `get_all_assets_detail unfiltered is paged in SQL for a restricted caller`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdIn(setOf(1L), any()) } returns onePage()

        val result = getAllAssetsDetail.execute(mapOf("pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
        verify { assetRepository.findByIdIn(setOf(1L), match { it.size == 50 }) }
    }

    @Test
    fun `get_all_assets_detail name filter is paged in SQL for a restricted caller`() = runBlocking {
        stubUnboundedReads()
        every {
            assetRepository.findByIdInAndNameContainingIgnoreCase(setOf(1L), "web", any())
        } returns onePage()

        val result = getAllAssetsDetail.execute(mapOf("name" to "web", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
    }

    @Test
    fun `get_all_assets_detail keeps admin on the paged repository calls`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findAll(any<Pageable>()) } returns onePage()

        val result = getAllAssetsDetail.execute(mapOf("pageSize" to 50), ctx(null))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
    }

    // ------------------------------------------------------------------ type/owner/ip/group

    @Test
    fun `get_assets type filter is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdInAndType(setOf(1L), "SERVER", any()) } returns onePage()

        val result = getAssets.execute(mapOf("type" to "SERVER", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
        verify { assetRepository.findByIdInAndType(setOf(1L), "SERVER", match { it.size == 50 }) }
    }

    @Test
    fun `get_assets owner filter is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdInAndOwner(setOf(1L), "alice", any()) } returns onePage()

        val result = getAssets.execute(mapOf("owner" to "alice", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
    }

    @Test
    fun `get_assets ip filter is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdInAndIpContainingIgnoreCase(setOf(1L), "10.0", any()) } returns onePage()

        val result = getAssets.execute(mapOf("ip" to "10.0", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
    }

    @Test
    fun `get_assets group filter is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdInAndGroupsContaining(setOf(1L), "prod", any()) } returns onePage()

        val result = getAssets.execute(mapOf("group" to "prod", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
    }

    @Test
    fun `get_assets type filter for admin is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByType("SERVER", any<Pageable>()) } returns onePage()

        val result = getAssets.execute(mapOf("type" to "SERVER", "pageSize" to 50), ctx(null))

        assertThat(result.isError).isFalse()
        assertNoUnboundedRead()
    }

    // --------------------------------------------- get_all_assets_detail honours its filters

    @Test
    fun `get_all_assets_detail applies the type filter it advertises`() = runBlocking {
        // These four filters were parsed and then ignored: the tool returned every asset while
        // claiming to filter, which is a silently wrong answer rather than a visible failure.
        stubUnboundedReads()
        every { assetRepository.findByIdInAndType(setOf(1L), "SERVER", any()) } returns onePage()

        val result = getAllAssetsDetail.execute(mapOf("type" to "SERVER", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify { assetRepository.findByIdInAndType(setOf(1L), "SERVER", match { it.size == 50 }) }
        verify(exactly = 0) { assetRepository.findByIdIn(any(), any()) }
    }

    @Test
    fun `get_all_assets_detail applies the owner filter it advertises`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdInAndOwner(setOf(1L), "alice", any()) } returns onePage()

        val result = getAllAssetsDetail.execute(mapOf("owner" to "alice", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify { assetRepository.findByIdInAndOwner(setOf(1L), "alice", any()) }
    }

    @Test
    fun `get_all_assets_detail applies the ip filter it advertises`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdInAndIpContainingIgnoreCase(setOf(1L), "10.0", any()) } returns onePage()

        val result = getAllAssetsDetail.execute(mapOf("ip" to "10.0", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify { assetRepository.findByIdInAndIpContainingIgnoreCase(setOf(1L), "10.0", any()) }
    }

    @Test
    fun `get_all_assets_detail applies the group filter it advertises`() = runBlocking {
        stubUnboundedReads()
        every { assetRepository.findByIdInAndGroupsContaining(setOf(1L), "prod", any()) } returns onePage()

        val result = getAllAssetsDetail.execute(mapOf("group" to "prod", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify { assetRepository.findByIdInAndGroupsContaining(setOf(1L), "prod", any()) }
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun `restricted unfiltered paging is ordered by name for determinism`() = runBlocking {
        // An unordered LIMIT/OFFSET can repeat or skip rows across pages. The previous Kotlin
        // implementation sorted by name before slicing; that ordering has to move into SQL.
        stubUnboundedReads()
        every { assetRepository.findByIdIn(setOf(1L), any()) } returns onePage()

        getAssets.execute(mapOf("pageSize" to 50), ctx(setOf(1L)))

        verify {
            assetRepository.findByIdIn(
                setOf(1L),
                match { it.sort.orderBy.any { order -> order.property == "name" } }
            )
        }
    }
}
