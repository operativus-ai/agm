/**
 * License feature keys. A nav item / tab tagged with one is hidden unless the
 * verified license (`useLicense().features`) includes it. Core ships none of
 * these features; the enterprise edition's manifests supply them. Typing the
 * `featureKey` field as `FeatureKey` (rather than bare string) makes a typo in
 * an edition manifest a compile error instead of a feature that silently never
 * appears.
 *
 * Keep in sync with the backend license `features[]` vocabulary.
 */
export const FEATURE_KEYS = {
  ORG_MANAGEMENT: 'org-management',
  ROUTING_POLICY: 'routing-policy',
  FINOPS: 'finops',
  ALERTING: 'alerting',
} as const;

export type FeatureKey = typeof FEATURE_KEYS[keyof typeof FEATURE_KEYS];
