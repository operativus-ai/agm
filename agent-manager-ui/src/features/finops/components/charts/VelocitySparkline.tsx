/**
 * Re-export from shared registry.
 * VelocitySparkline was lifted to shared/components/charts to prevent cross-feature domain leakage.
 * This re-export preserves backward compatibility for any remaining in-feature imports.
 */
export { VelocitySparkline } from '../../../../shared/components/charts/VelocitySparkline';
