package kr.msgctf.scheduler.db

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
class ReleaseIdMigrationTest {
    @Test
    fun `upgrades deployed v18 without losing cleanup evidence or container settings`() {
        flyway("18").migrate()
        val instanceId = UUID.randomUUID()
        val settings = """[{"name":"app","image":"ghcr.io/example/app:legacy","ports":[8080],"env":{"MODE":"old"},"secret_ref":"${UUID.randomUUID()}"}]"""
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO challenge_instance (
                    instance_id, team_id, user_id, challenge_id, status, containers,
                    create_failure_confirmed, created_at, updated_at, expires_at, hard_expires_at
                ) VALUES (?, ?, ?, ?, 'CLEANUP_PENDING', ?, true, now(), now(), now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.setString(5, settings)
                statement.executeUpdate()
            }
        }

        val current = flyway()
        assertEquals(2, current.migrate().migrationsExecuted)
        current.validate()
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT status, containers, create_failure_confirmed, release_id FROM challenge_instance WHERE instance_id = ?",
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("CLEANUP_PENDING", result.getString("status"))
                    assertEquals(settings, result.getString("containers"))
                    assertTrue(result.getBoolean("create_failure_confirmed"))
                    assertNull(result.getObject("release_id"))
                }
            }
        }
        assertEquals(0, current.migrate().migrationsExecuted)
    }

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
        if (target != null) config.target(target)
        return config.load()
    }

    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
    }
}
