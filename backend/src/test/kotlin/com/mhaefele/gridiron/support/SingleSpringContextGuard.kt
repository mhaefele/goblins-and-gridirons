package com.mhaefele.gridiron.support

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fail-fast if more than one Spring [org.springframework.context.ApplicationContext] is created
 * in this JVM. Lives in test sources only (component-scanned with the app) so every
 * `@SpringBootTest` fingerprint pays the cost.
 */
@Component
class SingleSpringContextGuard {
    init {
        val boot = boots.incrementAndGet()
        check(boot == 1) { "Spring ApplicationContext started $boot times in this JVM; expected one shared context (see BaseApiTest)" }
    }

    companion object {
        private val boots = AtomicInteger(0)
    }
}
