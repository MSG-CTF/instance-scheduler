package kr.msgctf.scheduler.instance.repository

import java.util.UUID
import kr.msgctf.scheduler.instance.domain.Instance
import org.springframework.data.jpa.repository.JpaRepository

interface InstanceRepository : JpaRepository<Instance, UUID>, ActiveInstanceFinder
