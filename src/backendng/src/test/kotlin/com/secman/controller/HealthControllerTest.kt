package com.secman.controller

import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import io.micronaut.http.HttpStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

class HealthControllerTest {

    @Test
    fun `health returns UP with 200 when database is reachable`() {
        val resultSet: ResultSet = mockk()
        every { resultSet.next() } returns true
        every { resultSet.close() } returns Unit

        val statement: Statement = mockk()
        every { statement.executeQuery("SELECT 1") } returns resultSet
        every { statement.close() } returns Unit

        val connection: Connection = mockk()
        every { connection.createStatement() } returns statement
        every { connection.close() } returns Unit

        val dataSource: DataSource = mockk()
        every { dataSource.connection } returns connection

        val controller = HealthController(dataSource)
        val response = controller.health()

        assertEquals(HttpStatus.OK, response.status)
        assertEquals("UP", response.body()!!.status)
        assertEquals("UP", response.body()!!.checks.database)
    }

    @Test
    fun `health returns DOWN with 503 when database connection fails`() {
        val dataSource: DataSource = mockk()
        every { dataSource.connection } throws SQLException("Connection refused")

        val controller = HealthController(dataSource)
        val response = controller.health()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status)
        assertEquals("DOWN", response.body()!!.status)
        assertEquals("DOWN", response.body()!!.checks.database)
    }

    @Test
    fun `health returns DOWN with 503 when the query hangs past the probe timeout`() {
        val connection: Connection = mockk()
        every { connection.createStatement() } answers {
            Thread.sleep(5000)
            mockk()
        }
        every { connection.close() } returns Unit

        val dataSource: DataSource = mockk()
        every { dataSource.connection } returns connection

        val controller = HealthController(dataSource)
        val response = controller.health()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status)
        assertEquals("DOWN", response.body()!!.status)
    }

    @Test
    fun `health returns UP with 200 when probe times out but Hikari pool is fully saturated`() {
        val connection: Connection = mockk()
        every { connection.createStatement() } answers { Thread.sleep(5000); mockk() }
        every { connection.close() } returns Unit

        val poolBean: HikariPoolMXBean = mockk()
        every { poolBean.activeConnections } returns 40
        every { poolBean.totalConnections } returns 40

        val dataSource: HikariDataSource = mockk()
        every { dataSource.connection } returns connection
        every { dataSource.hikariPoolMXBean } returns poolBean
        every { dataSource.maximumPoolSize } returns 40

        val controller = HealthController(dataSource)
        val response = controller.health()

        assertEquals(HttpStatus.OK, response.status)
        assertEquals("UP", response.body()!!.status)
        assertEquals("UP", response.body()!!.checks.database)
    }

    @Test
    fun `health returns DOWN with 503 when probe times out and Hikari pool has room`() {
        val connection: Connection = mockk()
        every { connection.createStatement() } answers { Thread.sleep(5000); mockk() }
        every { connection.close() } returns Unit

        val poolBean: HikariPoolMXBean = mockk()
        every { poolBean.activeConnections } returns 5
        every { poolBean.totalConnections } returns 10

        val dataSource: HikariDataSource = mockk()
        every { dataSource.connection } returns connection
        every { dataSource.hikariPoolMXBean } returns poolBean
        every { dataSource.maximumPoolSize } returns 40

        val controller = HealthController(dataSource)
        val response = controller.health()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status)
        assertEquals("DOWN", response.body()!!.status)
    }
}
