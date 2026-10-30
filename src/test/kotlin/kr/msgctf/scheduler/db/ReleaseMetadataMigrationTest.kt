package kr.msgctf.scheduler.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
class ReleaseMetadataMigrationTest {
    @TempDir
    lateinit var historicalMigrations: Path

    @Test
    fun `upgrades deployed gateway and windows schema without out of order migration or lost assignments`() {
        // The merged Gateway branch has V24 but has never contained our metadata migration.
        PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")
            .filter { resource ->
                val name = requireNotNull(resource.filename)
                name.substringBefore("__").removePrefix("V").toInt() <= 24 &&
                    !name.endsWith("__add_release_metadata_forwarded.sql")
            }.forEach { resource ->
                resource.inputStream.use { Files.copy(it, historicalMigrations.resolve(requireNotNull(resource.filename))) }
            }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("filesystem:$historicalMigrations").load().migrate()
        val instanceId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val leaseId = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO challenge_instance (
                    instance_id, team_id, user_id, challenge_id, status, containers,
                    created_at, updated_at, expires_at, hard_expires_at
                ) VALUES (?, ?, ?, ?, 'CLEANUP_PENDING', '[]', now(), now(), now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO windows_assignment (
                    submission_id, team_id, challenge_id, release_id, payload_sha256, lease_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, submissionId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.setString(5, "a".repeat(64))
                statement.setObject(6, leaseId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val current = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").load()
        assertEquals(4, current.migrate().migrationsExecuted)
        current.validate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.prepareStatement(
                "SELECT status, cleanup_generation, release_metadata_forwarded, reservation_release_reason, startup_cpu_millicores FROM challenge_instance WHERE instance_id = ?",
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("CLEANUP_PENDING", result.getString("status"))
                    assertEquals(0, result.getInt("cleanup_generation"))
                    assertNull(result.getObject("release_metadata_forwarded"))
                    assertNull(result.getObject("reservation_release_reason"))
                    assertNull(result.getObject("startup_cpu_millicores"))
                }
            }
            connection.prepareStatement("SELECT lease_id FROM windows_assignment WHERE submission_id = ?").use { statement ->
                statement.setObject(1, submissionId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(leaseId, result.getObject("lease_id", UUID::class.java))
                }
            }
            connection.prepareStatement(
                "UPDATE challenge_instance SET release_metadata_forwarded = true WHERE instance_id = ? RETURNING release_metadata_forwarded",
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(true, result.getObject(1))
                }
            }
        }
        assertEquals(0, current.migrate().migrationsExecuted)
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
    }
}
