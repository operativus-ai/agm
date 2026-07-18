package ai.operativus.agentmanager.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: prints a high-visibility WARN-level banner to the log when the
 *     'demo' Spring profile is active, so operators / reviewers cannot miss that the BE is
 *     running with seeded demo data and shared demo credentials.
 * State: Stateless (no fields).
 */
@Component
@Profile("demo")
public class DemoStartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DemoStartupBanner.class);

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.warn("");
        log.warn("================================================================");
        log.warn("                                                                ");
        log.warn("    ##  DEMO PROFILE ACTIVE  ##                                 ");
        log.warn("                                                                ");
        log.warn("    Liquibase contexts : demo                                   ");
        log.warn("    Seeded tenants     : DEMO_ACME, DEMO_GLOBEX                 ");
        log.warn("    Seeded agents      : 9 (demo_*)                             ");
        log.warn("    Seeded users       : demo-admin / demo-analyst /            ");
        log.warn("                         demo-viewer / demo-ops                 ");
        log.warn("    Shared password    : yamaha69                               ");
        log.warn("                                                                ");
        log.warn("    DO NOT use in production. Wipe with:                        ");
        log.warn("        ./scripts/reset-demo.sh                                 ");
        log.warn("                                                                ");
        log.warn("================================================================");
        log.warn("");
    }
}
