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
            // V21 전에 남은 이벤트도 순서 번호를 받아야 조회 정렬에서 빠지지 않는다
            // 시각이 늦은 이벤트를 먼저 넣어서, 번호가 테이블 순서가 아니라 시각 순서로 매겨지는지 본다
            listOf("later" to "2026-10-20T00:00:02Z", "earlier" to "2026-10-20T00:00:01Z").forEach { (detail, at) ->
                connection.prepareStatement(
                    """
                    INSERT INTO challenge_instance_event (event_id, instance_id, event_type, admin_detail, created_at)
                    VALUES (?, ?, 'STATE_CHANGED', ?, ?::timestamptz)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, instanceId)
                    statement.setString(3, detail)
                    statement.setString(4, at)
                    statement.executeUpdate()
                }
            }
        }

        val current = flyway()
        // V19 release_id, V20 replaced_instance_id, V21 event_seq
        assertEquals(3, current.migrate().migrationsExecuted)
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
            // 새 이벤트는 기존 번호 다음을 받는다, identity 시작값을 옮기지 않으면 1부터 다시 매겨 기존 번호와 겹친다
            connection.prepareStatement(
                """
                INSERT INTO challenge_instance_event (event_id, instance_id, event_type, admin_detail, created_at)
                VALUES (?, ?, 'STATE_CHANGED', 'new', now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, instanceId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "SELECT admin_detail, event_seq FROM challenge_instance_event WHERE instance_id = ? ORDER BY event_seq",
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.executeQuery().use { result ->
                    val rows = generateSequence { if (result.next()) result.getString(1) to result.getLong(2) else null }.toList()
                    assertEquals(listOf("earlier", "later", "new"), rows.map { it.first })
                    assertEquals(rows.map { it.second }.distinct().size, rows.size)
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
