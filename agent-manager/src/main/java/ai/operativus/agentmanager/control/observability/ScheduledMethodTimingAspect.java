package ai.operativus.agentmanager.control.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Domain Responsibility: Records a Micrometer {@link Timer} around every {@code @Scheduled}
 * method so SRE can see per-scheduler tick latency and error counts without waiting for the
 * downstream symptom (DB contention, backlog spike). Emits a single timer
 * {@code scheduler.tick.duration} tagged with {@code method=ClassName.methodName} and
 * {@code outcome=success|error}.
 *
 * <p>Relies on Spring's AspectJ auto-proxying (activated transitively via {@code spring-aspects}
 * + {@code aspectjweaver} on the classpath — no explicit {@code @EnableAspectJAutoProxy} needed
 * in modern Spring Boot). All {@code @Scheduled} methods live on Spring beans, so the aspect
 * fires both on container-dispatched ticks and on direct invocations through the Spring-injected
 * reference (the path used by {@code SchedulerTestSupport}).</p>
 *
 * State: Stateless (timers are created lazily on the injected {@link MeterRegistry}).
 */
@Aspect
@Component
public class ScheduledMethodTimingAspect {

    public static final String TIMER_NAME = "scheduler.tick.duration";

    private final MeterRegistry registry;

    public ScheduledMethodTimingAspect(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            outcome = "error";
            throw t;
        } finally {
            Timer.builder(TIMER_NAME)
                    .description("Per-method execution latency of @Scheduled tasks")
                    .tag("method", method)
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
