import type { NavGroup } from '../shared/nav/navManifest';

/**
 * Core-edition stub for the edition nav manifest. The `@ee/nav` alias resolves here in
 * the Core build, so no edition nav entry exists in Core source or its bundle. An edition
 * build (agm-enterprise/web) re-points the alias at its real manifest.
 * See docs/plans/agm-core-oss-execution.md §4.5.
 */
export const eeNavGroups: NavGroup[] = [];
