package kr.msgctf.scheduler.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kr.msgctf.scheduler.instance.domain.ContainerSpec
import kr.msgctf.scheduler.instance.service.ContainerSpecCodec
import org.flywaydb.core.Flyway
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

// V10 백필은 옮길 데이터가 있어야 실행되므로 빈 DB에서 돌리는 것만으로는 검증되지 않는다
// V9 시점 데이터를 넣고 최신까지 마이그레이션해 옮겨진 값을 확인한다
@Testcontainers(disabledWithoutDocker = true)
class ContainersBackfillMigrationTest {

    @Test
    fun `backfills v9 single container columns into containers json`() {
        migrate(target = "9")

        // V9 데이터는 태그 이미지도 있어 백필이 값을 고치지 않고 그대로 옮기는 것까지 확인한다
        val withImage = insertV9Row(image = "ghcr.io/example/web:latest", port = 8080)
        val withoutImage = insertV9Row(image = null, port = null)
        // 이미지와 포트 중 한쪽만 남은 행은 컨테이너를 구성할 수 없어 백필하지 않는다
        val imageOnly = insertV9Row(image = "ghcr.io/example/web:latest", port = null)

        migrate(target = null)

        assertEquals(
            listOf(
                ContainerSpec(
                    name = "challenge",
                    image = "ghcr.io/example/web:latest",
                    ports = listOf(8080),
                    expose = true,
                ),
            ),
            ContainerSpecCodec().decode(selectContainers(withImage)!!),
        )
        assertNull(selectContainers(withoutImage))
        assertNull(selectContainers(imageOnly))
    }

    // target이 null이면 최신까지 마이그레이션한다
    private fun migrate(target: String?) {
        val configuration = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
        if (target != null) {
            configuration.target(target)
        }
        configuration.load().migrate()
    }

    private fun insertV9Row(image: String?, port: Int?): UUID {
        val instanceId = UUID.randomUUID()
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO challenge_instance (
                    instance_id, team_id, user_id, challenge_id, status,
                    container_image, container_port,
                    created_at, updated_at, expires_at, hard_expires_at
                ) VALUES (?, ?, ?, ?, 'STOPPED', ?, ?, now(), now(), now(), now())
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, UUID.randomUUID())
                statement.setObject(4, UUID.randomUUID())
                statement.setString(5, image)
                if (port == null) {
                    statement.setNull(6, Types.INTEGER)
                } else {
                    statement.setInt(6, port)
                }
                statement.executeUpdate()
            }
        }
        return instanceId
    }

    private fun selectContainers(instanceId: UUID): String? =
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT containers FROM challenge_instance WHERE instance_id = ?",
            ).use { statement ->
                statement.setObject(1, instanceId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "instanceId=$instanceId row not found" }
                    resultSet.getString(1)
                }
            }
        }

    private fun connection(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
    }
}
