import type { RouteObject } from 'react-router-dom';

/**
 * Core-edition stub for the edition route manifest. The `@ee/routes` alias resolves here
 * in the Core build, so no edition route exists in Core source or its bundle. An edition
 * build (agm-enterprise/web) re-points the alias at its real manifest.
 * See docs/plans/agm-core-oss-execution.md §4.5.
 */
export const eeRoutes: RouteObject[] = [];
