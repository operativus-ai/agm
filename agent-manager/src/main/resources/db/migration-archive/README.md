# Archived Flyway Migrations

These V*.sql files are legacy migrations from before the project adopted Liquibase.
They are retained for historical reference only.

The active migration system is **Liquibase**, configured in `application.properties`:
```
spring.liquibase.change-log=classpath:db/changelog/db.changelog-master.xml
```

All new migrations should be added as Liquibase changesets under `db/changelog/`.
