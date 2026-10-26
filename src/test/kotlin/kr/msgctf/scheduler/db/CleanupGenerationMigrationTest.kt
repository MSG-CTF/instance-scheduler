package kr.msgctf.scheduler.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
class CleanupGenerationMigrationTest {
    @TempDir
    lateinit var deployedMigrations: Path

    @Test
    fun `upgrades deployed v23 without losing cleanup reservations or Windows assignments`() {
        // Production reached V23 without the unmerged cleanup migration. Reproduce that
        // exact history instead of letting a fresh database apply cleanup before V23.
        val migrations = PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql")
        for (migration in migrations) {
            val filename = checkNotNull(migration.filename)
            val version = filename.substringAfter("V").substringBefore("__").toInt()
            if (version !in 1..21 && version != 23) continue
            migration.inputStream.use { Files.copy(it, deployedMigrations.resolve(filename)) }
        }
        val deployed = flyway("filesystem:$deployedMigrations")
        assertEquals(22, deployed.migrate().migrationsExecuted)
        assertEquals("23", deployed.info().current().version.toString())

        val instanceId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val leaseId = UUID.randomUUID()
        val reservationId = "reservation-held-before-gateway-upgrade"
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = 'challenge_instance' AND column_name = 'cleanup_generation'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO challenge_instance (
                    instance_id, team_id, user_id, challenge_id, status, runtime_workload_id,
                    reservation_id, delete_reason, created_at, updated_at, expires_at, hard_expires_at
                ) VALUES (?, ?, ?, ?, 'CLEANUP_PENDING', 'node/ns', ?, 'USER_REQUESTED', now(), now(), now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.setString(5, reservationId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO windows_assignment (submission_id, team_id, challenge_id, release_id, payload_sha256, lease_id, worker_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, submissionId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.setString(5, "a".repeat(64))
                statement.setObject(6, leaseId)
                statement.setObject(7, UUID.randomUUID())
                statement.executeUpdate()
            }
        }

        val current = flyway("classpath:db/migration")
        assertFalse(current.configuration.isOutOfOrder)
        assertEquals(1, current.migrate().migrationsExecuted)
        current.validate()
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT status, runtime_workload_id, reservation_id, cleanup_generation FROM challenge_instance WHERE instance_id = ?",
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("CLEANUP_PENDING", result.getString("status"))
                    assertEquals("node/ns", result.getString("runtime_workload_id"))
                    assertEquals(reservationId, result.getString("reservation_id"))
                    assertEquals(0, result.getInt("cleanup_generation"))
                }
            }
            connection.prepareStatement("SELECT lease_id FROM windows_assignment WHERE submission_id = ?").use { statement ->
                statement.setObject(1, submissionId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(leaseId, result.getObject("lease_id", UUID::class.java))
                }
            }
        }
        assertEquals(0, current.migrate().migrationsExecuted)
    }

    private fun flyway(location: String): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations(location)
        .load()

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
    }
}
