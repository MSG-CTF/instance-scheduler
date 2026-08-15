package kr.msgctf.scheduler.instance.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

// 인스턴스 정책 설정 바인딩을 켠다
@Configuration
@EnableConfigurationProperties(InstancePolicyProperties::class)
class InstancePolicyConfig
