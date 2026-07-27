package kr.msgctf.scheduler.common.config

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer

// 응답의 시각 값을 밀리초 + 오프셋(+00:00) 형태로 고정한다
@Configuration
class JsonTimeFormatConfig {

    @Bean
    fun instantFormatCustomizer(): JsonMapperBuilderCustomizer =
        JsonMapperBuilderCustomizer { builder ->
            builder.addModule(
                SimpleModule().addSerializer(Instant::class.java, OffsetInstantSerializer()),
            )
        }

    class OffsetInstantSerializer : StdSerializer<Instant>(Instant::class.java) {

        override fun serialize(
            value: Instant,
            generator: JsonGenerator,
            context: SerializationContext,
        ) {
            generator.writeString(FORMATTER.format(value))
        }

        companion object {
            // 예: 2026-07-21T19:42:12.582+00:00
            // 소문자 x는 오프셋이 0이어도 Z 대신 +00:00을 쓴다
            private val FORMATTER: DateTimeFormatter =
                DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
                    .withZone(ZoneOffset.UTC)
        }
    }
}
